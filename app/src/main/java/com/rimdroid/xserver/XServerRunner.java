package com.rimdroid.xserver;

import android.util.Log;

import com.rimdroid.xconnector.UnixSocketConfig;
import com.rimdroid.xconnector.XConnectorEpoll;

import java.io.File;

/**
 * Boots the in-process X server (ported from Winlator, LGPL-2.1) for the RimWorld 1.6
 * X11/Vulkan runtime (see memory rimworld_16_port). One server per game session.
 *
 * The unix socket is created at {@code <rootDir>/tmp/.X11-unix/X0}; the guest side
 * (glibc libX11 under box64) connects to the hardcoded "/tmp/.X11-unix/X0", which
 * box64's connect() redirect maps to this host path (env RIMDROID_X11_SOCKET_DIR).
 */
public final class XServerRunner {
    private static final String TAG = "RimDroid/XServer";

    private static XServer xServer;
    private static XConnectorEpoll connector;

    private XServerRunner() {}

    /** Start the X server. @return the HOST path of the listening unix socket. */
    public static synchronized String start(String rootDir, int screenWidth, int screenHeight) {
        if (connector != null) stop();   // fresh session

        xServer = new XServer(new ScreenInfo(screenWidth, screenHeight));
        UnixSocketConfig socketConfig = UnixSocketConfig.create(rootDir, UnixSocketConfig.XSERVER_PATH);
        connector = new XConnectorEpoll(socketConfig,
                new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(4096);
        connector.setInitialOutputBufferCapacity(4096);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
        Log.i(TAG, "X server up: " + screenWidth + "x" + screenHeight
                + " socket=" + socketConfig.path);
        return socketConfig.path;
    }

    public static synchronized void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
        xServer = null;
        Log.i(TAG, "X server stopped");
    }

    public static synchronized XServer getXServer() {
        return xServer;
    }

    /** Root window's visual id — passed to the guest as SDL_VIDEO_X11_VISUALID so SDL takes our
     *  visual directly instead of XMatchVisualInfo (which was skipping our screen → 0 displays). */
    public static synchronized int getRootVisualId() {
        return xServer == null ? 0
                : xServer.windowManager.rootWindow.getContent().visual.id;
    }

    /** Directory that contains the created socket ("<rootDir>/tmp/.X11-unix"). */
    public static String socketDirOf(String rootDir) {
        return new File(rootDir, "tmp/.X11-unix").getAbsolutePath();
    }
}
