package com.rimdroid.xserver.requests;

import static com.rimdroid.xserver.Keyboard.KEYSYMS_PER_KEYCODE;
import static com.rimdroid.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.rimdroid.xconnector.XInputStream;
import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.Keyboard;
import com.rimdroid.xserver.XClient;
import com.rimdroid.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class KeyboardRequests {
    public static void getKeyboardMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        byte firstKeycode = inputStream.readByte();
        int count = inputStream.readUnsignedByte();
        inputStream.skip(2);

        // RimDroid fix: the reply must contain count * KEYSYMS_PER_KEYCODE keysyms. Winlator wrote
        // only `count` — a latent bug Wine never hit (it uses XKB), but SDL2's core
        // XGetKeyboardMapping does: libX11 allocates count*KPK CARD32s and reads PAST our short
        // reply → out-of-bounds keysym table → SIGSEGV in _XKeycodeToKeysym. Emit the full grid,
        // zero-filling keycodes beyond our table (so an over-wide min..255 request can't read OOB).
        int[] keysyms = client.xServer.keyboard.keysyms;
        int total = count * KEYSYMS_PER_KEYCODE;
        int base = (firstKeycode - Keyboard.MIN_KEYCODE) * KEYSYMS_PER_KEYCODE;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(KEYSYMS_PER_KEYCODE);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(total);       // reply length = keysyms_per_keycode * count
            outputStream.writePad(24);

            for (int j = 0; j < total; j++) {
                int idx = base + j;
                outputStream.writeInt(idx >= 0 && idx < keysyms.length ? keysyms[idx] : 0);
            }
        }
    }

    public static void getModifierMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);
            outputStream.writePad(24);
            outputStream.writePad(8);
        }
    }

    /** RimDroid stubs: SDL2's x11 keyboard setup calls these; fixed sane defaults. */
    public static void getKeyboardControl(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();
        try (com.rimdroid.xconnector.XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);           // reply
            outputStream.writeByte((byte)1);           // global auto repeat = On
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(5);                  // length (auto-repeats + tail)
            outputStream.writeInt(0);                  // led mask
            outputStream.writeByte((byte)50);          // key click percent
            outputStream.writeByte((byte)50);          // bell percent
            outputStream.writeShort((short)400);       // bell pitch
            outputStream.writeShort((short)100);       // bell duration
            outputStream.writePad(2);
            outputStream.writePad(32);                 // auto repeats bitmap (all off)
        }
    }

    public static void queryKeymap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();
        try (com.rimdroid.xconnector.XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);           // reply
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);                  // length
            outputStream.writePad(32);                 // no keys pressed
        }
    }
}