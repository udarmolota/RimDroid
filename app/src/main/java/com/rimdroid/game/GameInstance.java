package com.rimdroid.game;

import com.rimdroid.AppStorage;
import com.rimdroid.C;

import java.io.File;
import java.util.ArrayList;

public class GameInstance {

    private final String name;

    public GameInstance(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    /** Per-instance launch settings (renderer, Vulkan driver, debug, interpreter). */
    public com.rimdroid.InstanceSettings settings() {
        return new com.rimdroid.InstanceSettings(name);
    }

    public String getGamePath() {
        return AppStorage.requireSingleton().getInstanceDir(name).getAbsolutePath();
    }

    /**
     * RimWorld persistentDataPath inside this instance — holds {@code Saves/} and
     * {@code Config/} (used by save/settings backup &amp; restore).
     */
    public File getUserDataDir() {
        return new File(getGamePath(), "unity3d/Ludeon Studios/RimWorld by Ludeon Studios");
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

        ArrayList<String> paths = new ArrayList<>();

        // APK native libs (librimdroid.so, librimdroidlinker.so etc.)
        paths.add(storage.getLibraryPath());
        paths.add("/system/lib64");

        // ARM64 renderer libs — per this instance's renderer choice
        switch (settings().getRenderer()) {
            case GL4ES:
                paths.add(storage.getGl4esLibsPath());
                break;
            case ZINK_ZFA:
            case ZINK_OSMESA:
                paths.add(storage.getZinkLibsPath());
                break;
            case SOFTPIPE:
                // libOSMesa.so (softpipe CPU renderer) lives in the deps dir alongside libzfa.so.
                // This dir MUST be in the search path or rimdroid_ns can't resolve "libOSMesa.so"
                // by soname → rimdroid_init_osmesa()'s namespace dlopen returns NULL.
                paths.add(storage.getGl4esLibsPath());
                paths.add(storage.getZinkLibsPath());   // same deps dir; harmless if duplicate
                break;
        }

        return join(paths, ":");
    }

    /** Args passed to RimWorldLinux binary */
    public String[] getArgs() {
        // SPIKE toggle (RimWorld 1.6 bring-up, see [[rimworld_16_port]]): if a marker file
        // "rd_batchmode" exists in the instance dir, run HEADLESS (-batchmode -nographics).
        // This proves Mono-2022 + Burst + managed boot under box64 with the whole
        // window/render plane taken out of the equation. Toggle via adb, no rebuild:
        //   adb shell run-as com.rimdroid touch files/instances/<name>/rd_batchmode
        if (new File(getGamePath(), "rd_batchmode").exists()) {
            return new String[]{ "-batchmode", "-nographics" };
        }
        // X11 smoke test (RIMDROID_EXEC runs a foreign tool like xdpyinfo): no Unity args —
        // foreign binaries reject unknown options.
        if (new File(getGamePath(), "rd_x11_test").exists()) {
            return new String[]{};
        }
        // 1.6/X11+Vulkan render mode — see the rd_x11 block below. Threaded rendering is the
        // DEFAULT now (it ~doubles FPS: the GLX->zink bridge work moves off the main thread). The
        // earlier bring-up forced -force-gfx-direct because threaded rendering had a command-buffer
        // stall (never EndCommandBuffer -> kgsl climbs to ~2.8GB -> SIGABRT); the day's fixes healed
        // it on tested hardware, and devices that still can't survive the deep box64/Mono bug fall
        // back to single-threaded via COMPAT MODE.
        if (new File(getGamePath(), "rd_x11").exists()) {
            // Render mode (2026-07-11): threaded rendering ~doubles 1.6 FPS (62-65 vs 26-39) by
            // moving the GLX->zink bridge work off the main thread. DEFAULT = threaded for everyone.
            // Devices that can't survive the deep box64/Mono bug (SIGSEGV in libmonobdwgc during
            // def-load + destroyed-mutex abort — seen on e.g. Adreno 644/725, NOT a GPU-vendor
            // thing) use COMPAT MODE, which forces the safe single-threaded path (-force-gfx-direct
            // here + BOX64_MAXCPU=1 in GameLauncher). Marker "rd_gfxdirect" also forces single.
            boolean gfxDirect = settings().isCompatibilityMode()
                    || new File(getGamePath(), "rd_gfxdirect").exists();
            if (gfxDirect) {
                android.util.Log.i("RimDroid", "getArgs: rd_x11 -> single-threaded (-force-gfx-direct, compat/marker)");
                return new String[]{ "-force-gfx-direct" };
            }
            android.util.Log.i("RimDroid", "getArgs: rd_x11 -> threaded rendering (default 2-thread)");
            return new String[]{};
        }
        android.util.Log.i("RimDroid", "getArgs: default -force-gfx-direct (gamePath=" + getGamePath() + ")");
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
