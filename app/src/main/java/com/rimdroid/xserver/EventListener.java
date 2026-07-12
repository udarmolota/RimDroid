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
        try (com.rimdroid.xconnector.XStreamLock lock = client.getOutputStream().lock()) {
            event.send(client.getSequenceNumber(), client.getOutputStream());
            android.util.Log.i("RimDroid/XServer", "event -> " + event.getClass().getSimpleName());
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}
