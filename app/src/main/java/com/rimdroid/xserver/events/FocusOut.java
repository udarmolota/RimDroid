package com.rimdroid.xserver.events;

import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.Window;

import java.io.IOException;

/** RimDroid: FocusOut (code 10) — counterpart of {@link FocusIn}, same wire format. */
public class FocusOut extends Event {
    private final Window window;

    public FocusOut(Window window) {
        super(10);
        this.window = window;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte)3);      // detail = NotifyNonlinear
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(window.id);     // event window
            outputStream.writeByte((byte)0);      // mode = NotifyNormal
            outputStream.writePad(23);
        }
    }
}
