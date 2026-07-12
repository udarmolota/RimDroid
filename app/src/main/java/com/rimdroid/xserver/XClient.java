package com.rimdroid.xserver;

import androidx.collection.ArrayMap;

import com.rimdroid.xserver.util.Bitmask;
import com.rimdroid.xserver.util.Callback;
import com.rimdroid.xconnector.ConnectedClient;
import com.rimdroid.xconnector.XInputStream;
import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xserver.events.Event;

import java.io.IOException;
import java.util.ArrayList;

public class XClient extends ConnectedClient implements XResourceManager.OnResourceLifecycleListener {
    public final XServer xServer;
    private boolean authenticated = false;
    public final Integer resourceIDBase;
    private short sequenceNumber = 0;
    private int requestLength;
    private byte requestData;
    private int initialLength;
    private final ArrayMap<Window, EventListener> eventListeners = new ArrayMap<>();
    private final ArrayList<XResource> resources = new ArrayList<>();
    private final ArrayList<Callback<XClient>> onDestroyListeners = new ArrayList<>();

    public XClient(long nativePtr, int fd, XServer xServer) {
        super(nativePtr, fd);
        this.xServer = xServer;

        try (XLock lock = xServer.lockAll()) {
            resourceIDBase = xServer.resourceIDs.get();
            xServer.windowManager.addOnResourceLifecycleListener(this);
            xServer.pixmapManager.addOnResourceLifecycleListener(this);
            xServer.graphicsContextManager.addOnResourceLifecycleListener(this);
            xServer.cursorManager.addOnResourceLifecycleListener(this);
        }
    }

    public void registerAsOwnerOfResource(XResource resource) {
        resources.add(resource);
    }

    public void setEventListenerForWindow(Window window, Bitmask eventMask) {
        EventListener eventListener = eventListeners.get(window);
        if (eventListener != null) window.removeEventListener(eventListener);
        if (eventMask.isEmpty()) return;
        eventListener = new EventListener(this, eventMask);
        eventListeners.put(window, eventListener);
        window.addEventListener(eventListener);
    }

    // RimDroid 1.6 OnGUI-spin hunt: is Unity's endless OnGUI fed by an X-event flood from US?
    // Count every event we push to the client and log the running total + type periodically. If
    // this climbs into the millions during the hang, our X server is the flood source (our bug);
    // if it stays near zero, the spin is internal to Unity/RimWorld.
    private static long rd_eventsSent = 0;
    private static final java.util.HashMap<String, Long> rd_eventCounts = new java.util.HashMap<>();

    public void sendEvent(Event event) {
        // RimDroid: MUST write under the stream lock — closing it flushes the buffer to the
        // socket. Without it events (MapNotify etc.) sat in the native buffer forever and
        // SDL's blocking XIfEvent(MapNotify) after XMapRaised hung the game (see rimworld_16_port).
        try (com.rimdroid.xconnector.XStreamLock lock = outputStream.lock()) {
            event.send(sequenceNumber, outputStream);
            long n = ++rd_eventsSent;
            String type = event.getClass().getSimpleName();
            synchronized (rd_eventCounts) {
                rd_eventCounts.merge(type, 1L, Long::sum);
                if (n <= 20 || n % 5000 == 0)
                    android.util.Log.i("RimDroid-XEvt", "sent #" + n + " last=" + type + " byType=" + rd_eventCounts);
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isInterestedIn(int eventId, Window window) {
        EventListener eventListener = eventListeners.get(window);
        return eventListener != null && eventListener.isInterestedIn(eventId);
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public void freeResources() {
        try (XLock lock = xServer.lockAll()) {
            while (!resources.isEmpty()) {
                XResource resource = resources.remove(resources.size()-1);
                if (resource instanceof Window) {
                    xServer.windowManager.destroyWindow(resource.id);
                }
                else if (resource instanceof Pixmap) {
                    xServer.pixmapManager.freePixmap(resource.id);
                }
                else if (resource instanceof GraphicsContext) {
                    xServer.graphicsContextManager.freeGraphicsContext(resource.id);
                }
                else if (resource instanceof Cursor) {
                    xServer.cursorManager.freeCursor(resource.id);
                }
            }

            while (!eventListeners.isEmpty()) {
                int i = eventListeners.size()-1;
                eventListeners.keyAt(i).removeEventListener(eventListeners.removeAt(i));
            }

            xServer.windowManager.removeOnResourceLifecycleListener(this);
            xServer.pixmapManager.removeOnResourceLifecycleListener(this);
            xServer.graphicsContextManager.removeOnResourceLifecycleListener(this);
            xServer.cursorManager.removeOnResourceLifecycleListener(this);
            xServer.resourceIDs.free(resourceIDBase);
        }
    }

    public void generateSequenceNumber() {
        sequenceNumber++;
    }

    public short getSequenceNumber() {
        return sequenceNumber;
    }

    public int getRequestLength() {
        return requestLength;
    }

    public void setRequestLength(int requestLength) {
        this.requestLength = requestLength;
        initialLength = inputStream.available();
    }

    public byte getRequestData() {
        return requestData;
    }

    public void setRequestData(byte requestData) {
        this.requestData = requestData;
    }

    public int getRemainingRequestLength() {
        int actualLength = initialLength - inputStream.available();
        return requestLength - actualLength;
    }

    public void skipRequest() {
        inputStream.skip(getRemainingRequestLength());
    }

    public XInputStream getInputStream() {
        return inputStream;
    }

    public XOutputStream getOutputStream() {
        return outputStream;
    }

    public Bitmask getEventMaskForWindow(Window window) {
        EventListener eventListener = eventListeners.get(window);
        return eventListener != null ? eventListener.eventMask : new Bitmask();
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Window) eventListeners.remove(resource);
        resources.remove(resource);
    }

    public boolean isValidResourceId(int id) {
        return xServer.resourceIDs.isInInterval(id, resourceIDBase);
    }

    public void addOnDestroyListener(Callback<XClient> onDestroyListener) {
        if (!onDestroyListeners.contains(onDestroyListener)) onDestroyListeners.add(onDestroyListener);
    }

    public void removeOnWindowModificationListener(Callback<XClient> onDestroyListener) {
        onDestroyListeners.remove(onDestroyListener);
    }

    @Override
    public void destroy() {
        super.destroy();

        for (Callback<XClient> onDestroyListener : onDestroyListeners) onDestroyListener.call(this);
    }
}