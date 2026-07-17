package com.rimdroid.xserver;

import com.rimdroid.xserver.util.Bitmask;
import com.rimdroid.xserver.events.Event;

import java.io.IOException;

public class EventListener {
    public final XClient client;
    public final Bitmask eventMask;

    public EventListener(XClient client, Bitmask eventMask) {
        this.client = client;
        this.eventMask = eventMask;
    }

    public boolean isInterestedIn(int eventId) {
        return eventMask.isSet(eventId);
    }

    public boolean isInterestedIn(Bitmask mask) {
        return this.eventMask.intersects(mask);
    }

    public void sendEvent(Event event) {
        // RimDroid: write under the stream lock — closing it flushes to the socket. Without it
        // events never left the native buffer and SDL's blocking waits (MapNotify) hung. See
        // memory rimworld_16_port.
        // Capture the stream ONCE: a disconnecting client nulls its outputStream in destroy(), and
        // an async sender (e.g. the configure-kick thread, seconds after mapping) would otherwise
        // NPE — either straight away, or on the second getOutputStream() after the first locked
        // (the race that crashed the whole app on Adreno 735, 2026-07-17). A null stream = the
        // client is gone, so there's nothing to deliver.
        com.rimdroid.xconnector.XOutputStream out = client.getOutputStream();
        if (out == null) return;
        try (com.rimdroid.xconnector.XStreamLock lock = out.lock()) {
            event.send(client.getSequenceNumber(), out);
            android.util.Log.i("RimDroid/XServer", "event -> " + event.getClass().getSimpleName());
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}
