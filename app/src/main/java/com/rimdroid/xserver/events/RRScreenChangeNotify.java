package com.rimdroid.xserver.events;

import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;

import java.io.IOException;

/**
 * RandR RRScreenChangeNotify event (RandR event #0, so its X event code = RandR firstEvent base).
 * RimDroid 1.6 fix attempt: Unity's EARLY display probe fails ("SDL video subsystem not initialized")
 * and caches Display.main = 0×0; the LATER correct enumeration doesn't refresh managed Display.main.
 * SDL2 subscribes to RandR change events (XRRSelectInput). Delivering this event AFTER the real
 * enumeration is available should make SDL/Unity re-read the display via its own refresh path and
 * populate Display.main with the true 2340×1080 → unblocking the backbuffer-composite+present pipeline.
 * (AI consensus: cleanest possible fix, no UnityPlayer.so byte-patch, entirely in our X server.)
 */
public class RRScreenChangeNotify extends Event {
    private final int rootId;
    private final int windowId;
    private final short width, height, mmWidth, mmHeight;
    private final int timestamp;

    public RRScreenChangeNotify(byte firstEventBase, int rootId, int windowId,
                                short width, short height, short mmWidth, short mmHeight, int timestamp) {
        super(firstEventBase);   // RandR RRScreenChangeNotify = base + 0
        this.rootId = rootId;
        this.windowId = windowId;
        this.width = width;
        this.height = height;
        this.mmWidth = mmWidth;
        this.mmHeight = mmHeight;
        this.timestamp = timestamp;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);              // event type = RandR firstEvent + 0
            outputStream.writeByte((byte)1);           // rotation = RR_Rotate_0 (1)
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(timestamp);          // timestamp
            outputStream.writeInt(timestamp);          // config timestamp
            outputStream.writeInt(rootId);             // root
            outputStream.writeInt(windowId);           // request window
            outputStream.writeShort((short)0);         // sizeID
            outputStream.writeShort((short)0);         // subpixel order = Unknown
            outputStream.writeShort(width);            // width (pixels)
            outputStream.writeShort(height);           // height (pixels)
            outputStream.writeShort(mmWidth);          // width (mm)
            outputStream.writeShort(mmHeight);         // height (mm)
        }
    }
}
