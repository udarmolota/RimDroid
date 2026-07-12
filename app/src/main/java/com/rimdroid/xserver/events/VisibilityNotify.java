package com.rimdroid.xserver.events;

import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.Window;

import java.io.IOException;

/**
 * RimDroid: VisibilityNotify (code 12) was missing from the Winlator port. A real X server sends
 * it right after MapNotify; SDL2 uses it to mark the window visible. state=0 (Unobscured).
 */
public class VisibilityNotify extends Event {
    private final Window window;

    public VisibilityNotify(Window window) {
        super(15);
        this.window = window;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(window.id);
            outputStream.writeByte((byte)0);      // state = Unobscured
            outputStream.writePad(23);
        }
    }
}
