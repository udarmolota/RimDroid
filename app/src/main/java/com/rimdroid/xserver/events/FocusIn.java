package com.rimdroid.xserver.events;

import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.Window;

import java.io.IOException;

/**
 * RimDroid: FocusIn (code 9) was missing from the Winlator port. SDL2 relies on it to mark
 * SDL_WINDOW_INPUT_FOCUS after XSetInputFocus; without it the Unity 1.6 player never sees its
 * window focused. detail=Nonlinear(3), mode=Normal(0).
 */
public class FocusIn extends Event {
    private final Window window;

    public FocusIn(Window window) {
        super(9);
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
