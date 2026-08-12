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
            case MOBILEGLUES:   // same deps dir; the launcher maps it onto the GL4ES plumbing
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
            // MobileGlues (RIMDROID_GLT, exported by GameLauncher) runs single-threaded by
            // default: the bridge binds ONE EGL context, and on the 1.5/SDL path a second thread
            // meant a black screen at FPS 0. BUT the 1.6 glX bridge DOES implement the proper
            // unbind/rebind handoff (it carries zink's threaded mode every day), so threading may
            // just work here. RIMDROID_GLT_THREADED=1 in extra env is the opt-in experiment —
            // threading is zink's single biggest 1.6 lever (26-39 -> 62-65), worth chasing.
            boolean gltActive   = android.system.Os.getenv("RIMDROID_GLT") != null;
            boolean gltThreaded = "1".equals(android.system.Os.getenv("RIMDROID_GLT_THREADED"));
            boolean gfxDirect = settings().isCompatibilityMode()
                    || new File(getGamePath(), "rd_gfxdirect").exists()
                    || (gltActive && !gltThreaded);
            if (gfxDirect) {
                android.util.Log.i("RimDroid", "getArgs: rd_x11 -> single-threaded (-force-gfx-direct, compat/marker)");
                return new String[]{ "-force-gfx-direct" };
            }
            if (gltActive) android.util.Log.i("RimDroid", "getArgs: rd_x11 -> RIMDROID_GLT_THREADED=1, MobileGlues goes TWO-threaded (experiment)");
            android.util.Log.i("RimDroid", "getArgs: rd_x11 -> threaded rendering (default 2-thread)");
            return new String[]{};
        }
        // 1.5 (SDL/GL path). RIMDROID_NO_GFX_DIRECT=1 in extra env drops the flag here so threaded
        // rendering can be A/B'd on 1.5 without a build — 1.5 measures slower than 1.6 even with
        // fewer DLC, and sim+render sharing one thread is the obvious suspect. Opt-in only: the flag
        // predates that question, it is the guard against the black screen when the ZFA context has
        // to migrate between Unity's main and render threads (zfaReleaseCurrent is still a stub in
        // libzfa). Black screen => the migration really does need that symbol; more FPS => a real
        // lever for the 1.5 half of the audience.
        if ("1".equals(android.system.Os.getenv("RIMDROID_NO_GFX_DIRECT"))
                && android.system.Os.getenv("RIMDROID_GLT") == null) {
            // The threaded-rendering lever is a ZFA-only experiment. With MobileGlues (RIMDROID_GLT
            // set by the launcher) it must stay inert: the single EGL context cannot follow Unity's
            // render thread — threaded MG was a black screen at FPS 0 on the S25, and the Infinix
            // tester ran exactly this combination by accident (stale lever in the extra-env field).
            android.util.Log.i("RimDroid", "getArgs: RIMDROID_NO_GFX_DIRECT=1 -> threaded rendering (no -force-gfx-direct)");
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

    /**
     * Completeness check, separate from {@link #isInstalled()}. isInstalled() stays lenient (just the
     * RimWorldLinux binary) so a working instance NEVER vanishes from the launcher over a layout quirk —
     * but a repack/tarball can ship without the base game content, and RimWorld then dies at startup in
     * ModLister ("Sequence contains no matching element" — no Core) long after we've said "Installed".
     * This returns the list of REQUIRED files that are missing (empty = complete). Data/Core/About/
     * About.xml is the real tell (the base "Core" module); DLC (Data/Biotech, Data/Odyssey…) are
     * optional and intentionally NOT checked. Used to warn at install and block launch, not to hide.
     */
    public java.util.List<String> missingCoreFiles() {
        File root = new File(getGamePath());
        String[] required = {
            C.files.RIMWORLD_BIN,
            "RimWorldLinux_Data/Managed/Assembly-CSharp.dll",
            "Data/Core/About/About.xml",
        };
        java.util.List<String> missing = new ArrayList<>();
        for (String rel : required)
            if (!new File(root, rel).isFile()) missing.add(rel);
        // Existence alone is not enough: an interrupted download (the Steam downloader can die
        // mid-way on low-memory phones) leaves a truncated or empty RimWorldLinux that passes
        // isFile(). box64 then reports only "is not an executable file", which reads like an app
        // bug. Verify it really is an x86-64 ELF of plausible size, so the launcher can say
        // "incomplete, download again" instead.
        if (missing.isEmpty() && !isX86_64Elf(new File(root, C.files.RIMWORLD_BIN)))
            missing.add(C.files.RIMWORLD_BIN + " (incomplete or corrupted)");
        return missing;
    }

    /**
     * True if {@code f} is a complete x86-64 ELF executable: right magic/class/machine, and the
     * header and section tables it declares actually fit inside the file. Do NOT gate this on a
     * minimum size — RimWorldLinux is a ~14 KB launcher stub (the engine lives in UnityPlayer.so),
     * and an earlier size floor flagged healthy instances as incomplete.
     */
    private static boolean isX86_64Elf(File f) {
        final long len = f.length();
        if (!f.isFile() || len < 64) return false;             // smaller than an ELF64 header
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[64];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) return false;
                n += r;
            }
            if (!(h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F')) return false;
            if (h[4] != 2 || h[5] != 1) return false;          // ELFCLASS64, little-endian
            if (le16(h, 18) != 0x3e) return false;             // e_machine = EM_X86_64
            // Truncation check: a half-downloaded file keeps a valid header but loses the tail the
            // header points at, which is exactly what box64 reports as "not an executable file".
            long phEnd = le64(h, 32) + (long) le16(h, 54) * le16(h, 56);   // e_phoff + e_phentsize*e_phnum
            long shEnd = le64(h, 40) + (long) le16(h, 58) * le16(h, 60);   // e_shoff + e_shentsize*e_shnum
            return phEnd <= len && shEnd <= len;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    /** True if the base game content is present enough for RimWorld to load (see {@link #missingCoreFiles}). */
    public boolean isComplete() { return missingCoreFiles().isEmpty(); }

    private static String join(ArrayList<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
