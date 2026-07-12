package com.rimdroid.xserver.extensions;

import com.rimdroid.xconnector.XInputStream;
import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.XClient;
import com.rimdroid.xserver.XServer;
import com.rimdroid.xserver.errors.XRequestError;

import java.io.IOException;

import static com.rimdroid.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

/**
 * RimDroid addition (not from Winlator): MINIMAL RandR 1.3 — just enough for SDL2's
 * x11modes.c to enumerate one display. Unity 2022's static SDL requires XRandR for its
 * x11 video driver; without it SDL video init fails, Unity sees 0 displays and crashes
 * (see memory rimworld_16_port). We expose exactly one screen / output / crtc / mode:
 * the server's ScreenInfo size at 60 Hz. No events, no mode switching.
 */
public class RandRExtension extends Extension {
    private static final int CRTC_ID   = 0x52440001;
    private static final int OUTPUT_ID = 0x52440002;
    private static final int MODE_ID   = 0x52440003;
    private static final String OUTPUT_NAME = "RD-0";   // 4 chars → naturally 4-byte aligned
    private static final int TIMESTAMP = 1;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte SELECT_INPUT = 4;
        private static final byte GET_SCREEN_SIZE_RANGE = 6;
        private static final byte GET_SCREEN_RESOURCES = 8;
        private static final byte GET_OUTPUT_INFO = 9;
        private static final byte GET_CRTC_INFO = 20;
        private static final byte GET_SCREEN_RESOURCES_CURRENT = 25;
        private static final byte GET_OUTPUT_PRIMARY = 31;
        private static final byte GET_CRTC_TRANSFORM = 27;
        private static final byte GET_CRTC_GAMMA_SIZE = 22;
        private static final byte GET_CRTC_GAMMA = 23;
        private static final byte GET_PANNING = 28;
        private static final byte LIST_OUTPUT_PROPERTIES = 10;
    }

    // RandR event base: SDL/Unity subscribe via XRRSelectInput and expect RRScreenChangeNotify at this
    // event number. Core X events are 2..34, GenericEvent=35 — 64 is a safe extension base.
    private static final byte FIRST_EVENT = 64;
    private volatile boolean screenChangeScheduled = false;
    // The most recent client that issued any RANDR request. The notify thread must NOT capture
    // the scheduling client: Unity's first connection is a short-lived probe, and sending to it
    // after disconnect hit a null output stream (all 3 notifies silently failed for every run).
    private volatile XClient lastRandrClient;

    public RandRExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "RANDR";
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT;
    }

    /**
     * When SDL subscribes to RandR change events (XRRSelectInput), deliver RRScreenChangeNotify a few
     * seconds later — AFTER SDL's real display enumeration is available (Player.log "Desktop is 2340x1080")
     * — so Unity re-reads the display via its own refresh path and fills the managed Display.main
     * (systemWidth/renderingWidth), which the early failed probe left at 0×0. See RRScreenChangeNotify.
     */
    private void scheduleScreenChange(final XClient client) {
        if (screenChangeScheduled) return;
        screenChangeScheduled = true;
        final int rootId = xServer.windowManager.rootWindow.id;
        final short w = xServer.screenInfo.width, h = xServer.screenInfo.height;
        final short mw = xServer.screenInfo.getWidthInMillimeters(), mh = xServer.screenInfo.getHeightInMillimeters();
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 3; i++) {
                    try { Thread.sleep(6000); } catch (InterruptedException e) { return; }
                    // Resolve the target at send time: the scheduling client may be the dead probe.
                    XClient target = lastRandrClient;
                    if (target == null || target.getOutputStream() == null) {
                        android.util.Log.w("RimDroid/XServer", "RANDR notify #" + (i + 1) + ": no live client, skipping");
                        continue;
                    }
                    try {
                        android.util.Log.i("RimDroid/XServer", "RANDR -> RRScreenChangeNotify " + w + "x" + h + " (#" + (i + 1) + ")");
                        target.sendEvent(new com.rimdroid.xserver.events.RRScreenChangeNotify(
                                FIRST_EVENT, rootId, rootId, w, h, mw, mh, TIMESTAMP));
                    } catch (Exception e) {
                        android.util.Log.w("RimDroid/XServer", "RRScreenChange send err: " + e);
                    }
                }
            }
        }, "rd-randr-notify");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        lastRandrClient = client;
        int opcode = client.getRequestData();
        android.util.Log.i("RimDroid/XServer", "RANDR req minor=" + opcode
                + " screenInfo=" + xServer.screenInfo.width + "x" + xServer.screenInfo.height);
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                // SDL inits RandR here but does NOT call SELECT_INPUT (verified). Schedule the change
                // event unconditionally so Unity gets a chance to refresh its stale managed Display.main.
                scheduleScreenChange(client);
                break;
            case ClientOpcodes.SELECT_INPUT:
                client.skipRequest();   // window + event mask
                scheduleScreenChange(client);
                break;
            case ClientOpcodes.GET_SCREEN_SIZE_RANGE:
                getScreenSizeRange(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_SCREEN_RESOURCES:
            case ClientOpcodes.GET_SCREEN_RESOURCES_CURRENT:
                getScreenResources(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_OUTPUT_INFO:
                getOutputInfo(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_CRTC_INFO:
                getCrtcInfo(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_OUTPUT_PRIMARY:
                getOutputPrimary(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_CRTC_TRANSFORM:
                getCrtcTransform(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_PANNING:
                getPanning(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_CRTC_GAMMA_SIZE:
                getCrtcGammaSize(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_CRTC_GAMMA:
                getCrtcGamma(client, inputStream, outputStream);
                break;
            case ClientOpcodes.LIST_OUTPUT_PROPERTIES:
                listOutputProperties(client, inputStream, outputStream);
                break;
            default:
                android.util.Log.w("RimDroid/XServer", "RANDR: unsupported minor opcode " + opcode);
                client.skipRequest();
                new XRequestError(17, 0).sendError(client, getMajorOpcode());   // BadImplementation
                break;
        }
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // client major(4) + minor(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);    // major
            outputStream.writeInt(3);    // minor → RandR 1.3 (what SDL2 checks for)
            outputStream.writePad(16);
        }
    }

    private void getScreenSizeRange(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // window(4)
        short w = xServer.screenInfo.width, h = xServer.screenInfo.height;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort(w);  // min width  — fixed single mode
            outputStream.writeShort(h);  // min height
            outputStream.writeShort(w);  // max width
            outputStream.writeShort(h);  // max height
            outputStream.writePad(16);
        }
    }

    /** GetScreenResources and GetScreenResourcesCurrent share one reply layout. */
    private void getScreenResources(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // window(4)
        int nameBytes = OUTPUT_NAME.length();          // 4 — already 4-byte aligned
        int extra = 4 + 4 + 32 + nameBytes;            // crtcs + outputs + 1 RRModeInfo + names
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(extra / 4);
            outputStream.writeInt(TIMESTAMP);          // timestamp
            outputStream.writeInt(TIMESTAMP);          // config timestamp
            outputStream.writeShort((short)1);         // nCrtcs
            outputStream.writeShort((short)1);         // nOutputs
            outputStream.writeShort((short)1);         // nModes
            outputStream.writeShort((short)nameBytes); // nbytesNames
            outputStream.writePad(8);
            outputStream.writeInt(CRTC_ID);
            outputStream.writeInt(OUTPUT_ID);
            writeModeInfo(outputStream);
            for (int i = 0; i < nameBytes; i++) outputStream.writeByte((byte)OUTPUT_NAME.charAt(i));
        }
    }

    /** RRModeInfo (32 bytes): one mode = ScreenInfo size @60Hz (dotClock = w*h*60, totals = w/h). */
    private void writeModeInfo(XOutputStream outputStream) throws IOException {
        short w = xServer.screenInfo.width, h = xServer.screenInfo.height;
        outputStream.writeInt(MODE_ID);
        outputStream.writeShort(w);                    // width
        outputStream.writeShort(h);                    // height
        outputStream.writeInt(w * h * 60);             // dotClock → refresh = dotClock/(hTotal*vTotal) = 60
        outputStream.writeShort(w);                    // hSyncStart
        outputStream.writeShort(w);                    // hSyncEnd
        outputStream.writeShort(w);                    // hTotal
        outputStream.writeShort((short)0);             // hSkew
        outputStream.writeShort(h);                    // vSyncStart
        outputStream.writeShort(h);                    // vSyncEnd
        outputStream.writeShort(h);                    // vTotal
        outputStream.writeShort((short)0);             // nameLength = 0 (mode has no name string;
        // CRITICAL: nbytesNames in GetScreenResources counts output names + mode names. A non-zero
        // mode nameLength whose bytes we don't append would shift the whole names block → libXrandr
        // reads garbage mode dimensions → SDL gets a 0x0 display → Unity crashes. Keep it 0.
        outputStream.writeInt(0);                      // modeFlags
    }

    private void getOutputInfo(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // output(4) + config timestamp(4)
        int nameBytes = OUTPUT_NAME.length();
        // length = 1 (fixed part beyond 32 bytes) + nCrtcs + nModes + nClones + name words
        int length = 1 + 1 + 1 + 0 + (nameBytes + 3) / 4;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);           // status = Success
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(length);
            outputStream.writeInt(TIMESTAMP);          // timestamp
            outputStream.writeInt(CRTC_ID);            // current crtc
            outputStream.writeInt(xServer.screenInfo.getWidthInMillimeters());
            outputStream.writeInt(xServer.screenInfo.getHeightInMillimeters());
            outputStream.writeByte((byte)0);           // connection = Connected
            outputStream.writeByte((byte)0);           // subpixel order = Unknown
            outputStream.writeShort((short)1);         // nCrtcs
            outputStream.writeShort((short)1);         // nModes
            outputStream.writeShort((short)1);         // nPreferred
            outputStream.writeShort((short)0);         // nClones
            outputStream.writeShort((short)nameBytes); // name length
            outputStream.writeInt(CRTC_ID);            // possible crtcs
            outputStream.writeInt(MODE_ID);            // modes
            for (int i = 0; i < nameBytes; i++) outputStream.writeByte((byte)OUTPUT_NAME.charAt(i));
            outputStream.writePad((4 - (nameBytes % 4)) % 4);
        }
    }

    private void getCrtcInfo(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // crtc(4) + config timestamp(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);           // status = Success
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);                  // length: outputs(1) + possible(1)
            outputStream.writeInt(TIMESTAMP);
            outputStream.writeShort((short)0);         // x
            outputStream.writeShort((short)0);         // y
            outputStream.writeShort(xServer.screenInfo.width);
            outputStream.writeShort(xServer.screenInfo.height);
            outputStream.writeInt(MODE_ID);            // current mode
            outputStream.writeShort((short)1);         // rotation = Rotate_0
            outputStream.writeShort((short)1);         // rotations supported = Rotate_0
            outputStream.writeShort((short)1);         // nOutputs
            outputStream.writeShort((short)1);         // nPossibleOutputs
            outputStream.writeInt(OUTPUT_ID);
            outputStream.writeInt(OUTPUT_ID);
        }
    }

    /** GetCrtcTransform (minor 27): identity transform, no filters. SDL2 x11modes reads it per output. */
    private void getCrtcTransform(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // crtc(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            // fixed part after the 32-byte header: 2 transforms (2*36) + 2 filter name strs (empty,
            // padded to 4 each = "" needs a 0-length name → the 2 nbytes shorts sit in the header).
            // X reply length = (total_bytes - 32) / 4. Total = 8-byte header + body(88) = 96,
            // body = pendingTransform(36) + hasTransforms(1)+pad(3) + currentTransform(36) + pad(4)
            // + 4 shorts(8) = 88. length = (96-32)/4 = 16.
            outputStream.writeInt(16);
            writeIdentityTransform(outputStream);      // pendingTransform (36)
            outputStream.writeByte((byte)0);           // hasTransforms = false
            outputStream.writePad(3);
            writeIdentityTransform(outputStream);      // currentTransform (36)
            outputStream.writePad(4);
            outputStream.writeShort((short)0);         // pendingLen (filter name)
            outputStream.writeShort((short)0);         // pendingNparams
            outputStream.writeShort((short)0);         // currentLen
            outputStream.writeShort((short)0);         // currentNparams
        }
    }

    /** RRTransform = 3x3 matrix of 16.16 fixed-point; identity = diag(1,1,1). */
    private void writeIdentityTransform(XOutputStream outputStream) throws IOException {
        int one = 1 << 16;
        int[] m = { one, 0, 0,  0, one, 0,  0, 0, one };
        for (int v : m) outputStream.writeInt(v);
    }

    /** ListOutputProperties (minor 10): no properties (SDL only wanted EDID, optional). */
    private void listOutputProperties(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // output(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);                  // length
            outputStream.writeShort((short)0);         // nAtoms
            outputStream.writePad(22);
        }
    }

    private static final int GAMMA_SIZE = 256;

    /** GetCrtcGammaSize (minor 22): fixed 256-entry ramp. */
    private void getCrtcGammaSize(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // crtc(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)GAMMA_SIZE);
            outputStream.writePad(22);
        }
    }

    /** GetCrtcGamma (minor 23): identity/linear ramp for R, G, B. */
    private void getCrtcGamma(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // crtc(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((3 * GAMMA_SIZE * 2) / 4);   // r+g+b CARD16 ramps
            outputStream.writeShort((short)GAMMA_SIZE);
            outputStream.writePad(22);
            for (int c = 0; c < 3; c++)
                for (int i = 0; i < GAMMA_SIZE; i++) outputStream.writeShort((short)(i << 8 | i));
        }
    }

    /** GetPanning (minor 28): panning disabled (all zeros). xrandr queries it per crtc. */
    private void getPanning(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // crtc(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);           // status = Success
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(1);                  // body beyond 32: (36-32)/4 = 1
            outputStream.writeInt(TIMESTAMP);
            outputStream.writePad(24);                 // pan/track rects + borders = all 0 (disabled)
        }
    }

    private void getOutputPrimary(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();   // window(4)
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(OUTPUT_ID);
            outputStream.writePad(20);
        }
    }
}
