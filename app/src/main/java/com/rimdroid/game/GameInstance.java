package com.rimdroid.game;

import com.rimdroid.AppStorage;
import com.rimdroid.C;
import com.rimdroid.LauncherPreferences;

import java.io.File;
import java.util.ArrayList;

public class GameInstance {

    private final String name;

    public GameInstance(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public String getGamePath() {
        return AppStorage.requireSingleton().getInstanceDir(name).getAbsolutePath();
    }

    /**
     * x86_64 library search path for box64 (BOX64_LD_LIBRARY_PATH).
     * Contains ONLY x86_64 libraries — game libs and Linux system libs.
     * ARM64 renderer libs do NOT belong here.
     */
    public String getLdLibraryPathForEmulation() {
        AppStorage storage = AppStorage.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();

        String gameDir = getGamePath();
        // Unity data dir is always named {Executable}_Data for Linux builds
        String dataDir = gameDir + "/RimWorldLinux_Data";

        // Game root dir (top-level .so files, if any)
        paths.add(gameDir);

        // Mono runtime — libmonobdwgc-2.0.so, libMonoPosixHelper.so
        paths.add(dataDir + "/MonoBleedingEdge/x86_64");

        // Game plugins (x86_64) — ScreenSelector.so etc.
        paths.add(dataDir + "/Plugins/x86_64");

        // Game plugins (no arch suffix) — libsteam_api.so, libCSteamworks.so etc.
        paths.add(dataDir + "/Plugins");

        // x86_64 system libs — libgcc_s.so.1 etc.
        paths.add(storage.getLibsLinuxX86Path());

        return join(paths, ":");
    }

    /**
     * ARM64 native library path — passed to Android linker for loading
     * our ARM64 .so files (renderer, APK native libs).
     */
    public String getNativeLibraryPath() {
        AppStorage storage = AppStorage.requireSingleton();
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();

        // APK native libs (librimdroid.so, librimdroidlinker.so etc.)
        paths.add(storage.getLibraryPath());
        paths.add("/system/lib64");

        // ARM64 renderer libs
        switch (prefs.getRenderer()) {
            case GL4ES:
                paths.add(storage.getGl4esLibsPath());
                break;
            case ZINK_ZFA:
            case ZINK_OSMESA:
                paths.add(storage.getZinkLibsPath());
                break;
        }

        return join(paths, ":");
    }

    /** Args passed to RimWorldLinux binary */
    public String[] getArgs() {
        // -force-gfx-direct: disable Unity's threaded render device (threaded=1).
        // Our single ZFA/Zink GL context is made current on one thread only;
        // a separate render thread would have no current GL context. Forcing the
        // direct (single-threaded) GfxDevice keeps all GL on one thread for bring-up.
        return new String[]{ "-force-gfx-direct" };
    }

    public boolean isInstalled() {
        return new File(getGamePath(), C.files.RIMWORLD_BIN).exists();
    }

    private static String join(ArrayList<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
