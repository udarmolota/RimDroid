package com.rimdroid.xserver.events;

import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.Window;

import java.io.IOException;

/**
 * RimDroid: ReparentNotify (code 21) was missing from the Winlator port. A real X server reports
 * every reparent to StructureNotify subscribers; a client that reparents its own window (the Unity
 * 1.6 player does at startup) may block waiting for it.
 */
public class ReparentNotify extends Event {
    private final Window event;
    private final Window window;
    private final Window parent;

    public ReparentNotify(Window event, Window window, Window parent) {
        super(21);
        this.event = event;
        this.window = window;
        this.parent = parent;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(event.id);
            outputStream.writeInt(window.id);
            outputStream.writeInt(parent.id);
            outputStream.writeShort(window.getX());
            outputStream.writeShort(window.getY());
            outputStream.writeByte((byte)(window.attributes.isOverrideRedirect() ? 1 : 0));
            outputStream.writePad(11);
        }
    }
}
