package com.rimdroid;

import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import android.util.Log;

import com.rimdroid.game.GameInstance;

import java.io.File;

public class GameLauncher {

    private static final String TAG = "RimDroid/GameLauncher";

    // Callback for passing log lines to the UI
    public interface LogCallback {
        void onLogLine(String line);
    }

    private static LogCallback logCallback;
    private static LogcatReader logcatReader;

    public static void setLogCallback(LogCallback callback) {
        logCallback = callback;
    }

    public static void postLog(String line) {
        if (logCallback != null) logCallback.onLogLine(line);
    }

    /** Device SoC fingerprint for the log: chip maker/model (API 31+) + the always-available hardware
     *  string. Together with the GL_RENDERER line (GPU) this identifies a tester's device at a glance —
     *  no more guessing which device a log came from. */
    private static String deviceSoc() {
        StringBuilder sb = new StringBuilder();
        if (android.os.Build.VERSION.SDK_INT >= 31)
            sb.append(android.os.Build.SOC_MANUFACTURER).append(' ').append(android.os.Build.SOC_MODEL).append(' ');
        sb.append("[hw=").append(android.os.Build.HARDWARE).append(']');
        return sb.toString().trim();
    }

    /** Active mods in load order, parsed from the instance's Config/ModsConfig.xml. Logged in the launch
     *  config (rimdroid.log) so we have the mod set + ORDER even when the game crashes DURING mod load
     *  (RimWorld's own mod-list log is lost to such a crash — exactly the case that hides mod/Harmony bugs).
     *  Reveals at a glance: is our Harmony present, is it FIRST, how many mods, wrong order. */
    private static String readActiveMods(GameInstance gi) {
        try {
            java.io.File cfg = new java.io.File(gi.getUserDataDir(), "Config/ModsConfig.xml");
            if (!cfg.isFile()) return "(ModsConfig.xml not found — run the game once)";
            StringBuilder x = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(cfg))) {
                String line;
                while ((line = r.readLine()) != null) x.append(line).append('\n');
            }
            String s = x.toString();
            int a = s.indexOf("<activeMods>"), b = s.indexOf("</activeMods>");
            if (a < 0 || b < 0) return "(no <activeMods> block)";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<li>([^<]+)</li>").matcher(s.substring(a, b));
            StringBuilder sb = new StringBuilder();
            int i = 1;
            while (m.find()) sb.append("\n                  ").append(i++).append(". ").append(m.group(1).trim());
            return sb.length() == 0 ? "(none active)" : sb.toString();
        } catch (Exception e) { return "(read failed: " + e.getMessage() + ")"; }
    }

    private static String buildLaunchConfig(GameInstance gi, LauncherPreferences.Renderer actualRenderer,
                                            GpuInfo gpu,
                                            VulkanDriverPolicy.Decision driverDecision) {
        InstanceSettings s = gi.settings();
        String configuredSo = driverDecision != null
                ? driverDecision.configuredSo : s.getVulkanDriverSo();
        String policySo = driverDecision != null
                ? driverDecision.effectiveSo : configuredSo;
        String actualSo = Os.getenv("RIMDROID_VULKAN_DRIVER_NAME");
        if (actualSo == null) actualSo = policySo;
        String decisionReason = driverDecision != null
                ? driverDecision.reason : "renderer does not use Vulkan driver policy";
        if (!actualSo.equals(policySo)) {
            decisionReason += "; overridden by Extra env vars";
        }
        boolean interp = s.isInterpreter();
        return "=== RimDroid launch config ===\n"
            + "instance      : " + gi.getName() + "\n"
            + "app version   : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
            + "device        : " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " (Android " + android.os.Build.VERSION.RELEASE + ", API " + android.os.Build.VERSION.SDK_INT + ")\n"
            + "soc           : " + deviceSoc() + "\n"
            + "renderer      : " + actualRenderer.name() + "\n"
            + "gpu probe     : " + (gpu != null
                    ? gpu.displayName() + " / " + (gpu.vendor != null ? gpu.vendor : "unknown vendor")
                    : "not queried") + "\n"
            + "vulkan config : " + VulkanDriverPolicy.displayName(configuredSo) + "\n"
            + "vulkan actual : " + VulkanDriverPolicy.displayName(actualSo) + "\n"
            + "driver policy : " + decisionReason + "\n"
            + "render scale  : " + s.getRenderScalePercent() + "%\n"
            + "debug         : " + (s.isDebug() ? "ON" : "off") + "\n"
            + "interpreter   : " + (interp ? "ON (dynarec OFF)" : "off") + "\n"
            + "compat mode   : " + (s.isCompatibilityMode() ? "ON (WEAKBARRIER=2 X87DOUBLE=1 MAXCPU=1)" : "off") + "\n"
            + "box64         : DYNAREC=" + (interp ? "0" : "1")
                + " STRONGMEM=4 BIGBLOCK=0 SAFEFLAGS=1 WEAKBARRIER=" + (s.isCompatibilityMode() ? "2 X87DOUBLE=1 MAXCPU=1" : "1") + "\n"
            + "extra env     : " + envFieldReport(s) + "\n"
            + "active mods   : " + readActiveMods(gi) + "\n"
            + "(GL_RENDERER / GL_VERSION appear below once GL initialises)\n"
            + "==============================";
    }

    /**
     * Diagnostic fingerprint of the user-entered "Extra env vars" field. Prints the raw string AND
     * reads each key back from the live process environment via {@link Os#getenv} — proving (a) the
     * user actually entered something and it was saved, and (b) it was applied to the environment that
     * box64 forwards to the guest. "(NULL!)" means the setenv did not stick (e.g. malformed token).
     * Called from buildLaunchConfig, which runs AFTER the env field is applied, so the readback is valid.
     */
    private static String envFieldReport(InstanceSettings s) {
        String raw = s.getEnvVars();
        if (raw == null || raw.trim().isEmpty()) return "(none entered)";
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(raw.trim()).append('"');
        for (String token : raw.trim().split("\\s+")) {
            String[] parts = token.split("=", 2);
            if (parts.length == 2) {
                String k = parts[0].trim();
                String got = Os.getenv(k);
                sb.append("\n                  ").append(k).append(" → getenv=")
                  .append(got == null ? "(NULL!)" : got);
            } else {
                sb.append("\n                  [malformed token, no '=']: ").append(token);
            }
        }
        return sb.toString();
    }

    public static void launch(GameInstance gameInstance) throws ErrnoException {

        // --- Audio (always on) ---
        // Load the libasound→AAudio output shim on every launch. Its DT_SONAME is "libasound.so.2", so
        // loading it here registers it under that soname before the guest FMOD's dlopen("libasound.so.2")
        // runs → FMOD output reaches AAudio. Best-effort: if it fails to load, the game just runs silent.
        // HISTORY: this used to be GATED behind a global toggle + the RimDroidSound PCM pack, because
        // box64 mis-decoded Vorbis (screech) so raw stock audio was unusable. The box64 qsort_r fix
        // (wrappedlibc.c) repaired FMOD's Vorbis codebook build, so RAW Vorbis now decodes clean —
        // verified 2026-07-14 (DLC mech SFX + the full instrumental soundtrack). Sound just works now,
        // so there's no toggle and no pack dependency: the shim always loads.
        try {
            System.loadLibrary("asoundshim");
            postLog("Audio: libasound→AAudio shim loaded (raw Vorbis)");
        } catch (Throwable t) {
            Log.e(TAG, "asound shim load failed; game will be silent", t);
        }

        // --- Box64 tuning ---
        // BOX64_LOG: 0 normally (verbose tracing = gigabyte logs). Raise to 1-2
        // only for targeted call-sequence tracing.
        Os.setenv("BOX64_LOG", "0", true);
        Os.setenv("BOX64_SHOWBT", "1", true);
        Os.setenv("BOX64_DYNAREC", "1", true);
        Os.setenv("BOX64_DYNAREC_BIGBLOCK", "0", true);  // 0 for Unity/Mono JIT
        Os.setenv("BOX64_DYNAREC_SAFEFLAGS", "1", true);
        Os.setenv("BOX64_DYNAREC_STRONGMEM", "4", true);    // QEMU-style strong memory model. Tested on Adreno 830: 4 > 3 > 2 for FPS (more barriers → fewer Mono-GC fault-storms; strictest = also safest for saves). Kept at 4.
        Os.setenv("BOX64_DYNAREC_WEAKBARRIER", "1", true);  // box64 default; WEAKBARRIER=0 was tested, did NOT fix save corruption
        // FASTNAN/FASTROUND default to 1 in box64 (imprecise FP). Force OFF: imprecise FP is a known
        // amplifier of the pawn-save corruption (multiple reports got corruption ONLY after a third-party
        // AI told them to set these to 1). Precise FP costs a little speed, safety wins.
        Os.setenv("BOX64_DYNAREC_FASTNAN", "0", true);
        Os.setenv("BOX64_DYNAREC_FASTROUND", "0", true);
        // TEMPORARY tester experiment (DEBUG builds only): force box64's aligned-atomics CAS path to
        // test the Mali/Cortex save-corruption hypothesis — Mono's GC CMPXCHG may be hitting box64's
        // "unaligned atomic" fallback (marked "not enough" in box64 source) → corrupting Pawn objects →
        // pawns serialize as empty <li/>. Debug-only so the signed release is unaffected. If this fixes
        // the save bug on the affected device, we make it a proper per-device default. Remove afterwards.
        // (Ruled out as the softpipe "all-zero buffer" cause — re-enabled for the Mali/Cortex test.)
        if (BuildConfig.DEBUG) {
            Os.setenv("BOX64_DYNAREC_ALIGNED_ATOMICS", "1", true);
        }
        // BOX64_DYNAREC_DIRTY=2 TESTED 2026-06-03 (Adreno 830): REJECTED. FPS collapsed to 12→7 (got WORSE over
        // time, opposite of cold-cache warmup) — NEVERCLEAN hotpages break Mono-JIT SMC handling. Keep default (0).
        // "Interpreter mode" test toggle → disable box64 dynarec entirely (BOX64_DYNAREC=0,
        // run x86_64 via the interpreter). VERY slow, but the DECISIVE diagnostic for the save
        // corruption: if pawns serialize correctly with the dynarec OFF, the bug is a dynarec
        // codegen miscompile (fixable via a box64 flag/patch); if they're STILL empty, it's in
        // box64's wrapper / atomic emulation (common to both paths). Earlier this toggle tried
        // WEAKBARRIER=0 and DF=0 — neither fixed the save, so we go to the interpreter.
        if (gameInstance.settings().isInterpreter()) {
            Os.setenv("BOX64_DYNAREC", "0", true);
        }
        // Compatibility mode → box64 dynarec FP/barrier tuning that dodges the deep "won't launch past the
        // loading dots / black screen" bug on affected devices (Adreno 610/725, weak-Vulkan Mali). Tester-
        // discovered: WEAKBARRIER=2 (looser memory barriers) + X87DOUBLE=1 (64-bit x87, no 80↔64 spill) →
        // reshapes the FP↔GPR codegen so the bad pattern is avoided. Default OFF (Settings) so devices that
        // already launch keep the safer/faster defaults. Applied before the env_vars field so a power user
        // can still override. NOTE: a workaround, not the root fix (the deep bug is still being chased).
        // RimWorld 1.6 (rd_x11): the load hangs in an ASYNC LongEvent whose worker thread SPINS (R
        // state) — the SAME box64 miscompile of .NET self-replicating Parallel tasks that black-
        // screened 1.5 def-load (ShortHashGiver.GiveAllShortHashes via Parallel.ForEach). Isolate the
        // known fix WITHOUT the FP/barrier tuning: cap ProcessorCount to 1 so Parallel.ForEach runs
        // inline/serial and never spawns the self-replicating workers. If this lets the load finish
        // and a frame present, the "OnGUI repaint spin" was just the main thread waiting on the stuck
        // parallel loader. Needs the box64 my_sched_getaffinity + syscall-204 cap (already in tree).
        if (new java.io.File(gameInstance.getGamePath(), "rd_x11").exists()) {
            // FPS (2026-07-11 night): 1.6 is CPU/thread-bound (GPU ~90% idle). BOX64_MAXCPU=1
            // collapsed Mono onto 1 CPU and HALVED gameplay FPS (26-39 capped vs 62-65 uncapped,
            // Adreno 830). The cap is NOT needed by default — it belongs to COMPAT MODE, which the
            // devices hit by the deep box64/Mono bug (def-load SIGSEGV + destroyed-mutex abort;
            // e.g. Adreno 644/725 — NOT a GPU-vendor split) turn on. So DEFAULT = all cores; compat
            // mode (below) applies MAXCPU=1 + -force-gfx-direct together.
            // textureCompression: OFF by default (the BC-compress shader hangs Turnip/A830). The
            // "rd_texcompress" marker flips it ON to TEST the box64 CompressBC loop-bounding shim
            // (see rd_bc_bound_loops in wrappedsdl2.c) — if that shim tames the hang, compression
            // comes back and DLC fits in memory on 8GB devices.
            boolean testCompress = new java.io.File(gameInstance.getGamePath(), "rd_texcompress").exists();
            PrefsXml.pinTextureCompression(new java.io.File(gameInstance.getGamePath(),
                    "unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Config"), testCompress);
            if (testCompress)
                android.util.Log.i("RimDroid", "GameLauncher: rd_texcompress -> textureCompression=True (loop-cap shim test)");
        }
        // Native box64 save-fix scanner OFF for EVERY launch (2026-07-14). The save-bug ROOT was
        // PROVEN to be box64's broken Android qsort mis-sorting Mono's IMT collision entries
        // (imt_sort_slot_entries → imt_emit_ir; shadow-sorter experiment + libmono disassembly) —
        // fixed at the root in wrappedlibc.c, so the scanner band-aid is redundant on 1.5 too, and
        // on 1.6 it actively CRASHED saves (1.5-Mono offsets on 2022.3 Mono). Code stays in the
        // tree (rd_savefix_off() checks mere PRESENCE of this env var); the shadow-sorter
        // diagnostic (RIMDROID QSORTDIV) stays active as the sensor for the no-fix validation runs.
        Os.setenv("RIMDROID_NO_SAVEFIX", "1", true);
        // RimWorld 1.6 GLES pivot: presence of an "rd_force_gles" marker denies Vulkan to Unity
        // (my_vkCreateInstance -> VK_ERROR_INCOMPATIBLE_DRIVER) so its auto graphics-API selection
        // falls back to the GfxDeviceGLES backend, which presents via native EGL and bypasses the
        // stuck Vulkan display/present gate. Make-or-break test: does Unity fall back to GLES?
        if (new java.io.File(gameInstance.getGamePath(), "rd_force_gles").exists()) {
            android.util.Log.i("RimDroid", "GameLauncher: rd_force_gles -> RIMDROID_FORCE_GLES=1 (deny Vulkan, force GLES fallback)");
            Os.setenv("RIMDROID_FORCE_GLES", "1", true);
        }
        if (gameInstance.settings().isCompatibilityMode()) {
            Os.setenv("BOX64_DYNAREC_WEAKBARRIER", "2", true);
            Os.setenv("BOX64_DYNAREC_X87DOUBLE", "1", true);
            // NOTE: FORWARD=0 was tried here too but REMOVED — a tester reported it made things WORSE
            // (smaller blocks → MORE block boundaries → more FP↔GPR-boundary leaks; the bug is at the
            // boundary transition). The proven pair is WEAKBARRIER=2 + X87DOUBLE=1.
            // MAXCPU=1 → guest sees a single CPU → RimWorld 1.5's parallel Def load (ShortHashGiver
            // .GiveAllShortHashes via Parallel.ForEach) runs inline/serially, avoiding the older
            // Task.ExecuteSelfReplicating path box64 miscompiled into a worker NRE ("Caught exception
            // while loading play data … Resetting mods config" → mods fail / black on 725 / Mali-G57).
            // CAVEAT (verified 2026-07-18, Adreno 710 / RimWorld 1.6.4633): on 1.6 this does NOT stop
            // the def-load crash — even with the affinity cap firing (ProcessorCount→1), 1.6's newer
            // TPL TaskReplicator still runs and the SAME fatal RIP (libmono+0x111610) also faults from
            // serial XmlTextReaderImpl, so the crash is a box64 SMC-page fault (false-MAPERR 2-hit cap),
            // NOT the parallel path. So MAXCPU=1 is a 1.5 mod-load workaround only; do not treat it as a
            // 1.6 def-load fix. Needs the box64 my_sched_getaffinity cap (wrappedlibc.c) to drop
            // ProcessorCount. See [[save_bug_investigation]] / [[known_bugs]].
            Os.setenv("BOX64_MAXCPU", "1", true);
        }
        // BOX64_PREFER_EMULATED intentionally NOT set:
        // with prefer_emulated=1 box64 skips initWrappedLib for all non-essential libs,
        // including SDL2 — our my2_SDL_DYNAPI_entry never fires.
        // glibc/libm/libpthread are essential and stay wrapped regardless.

        // box64 log
        Os.setenv("BOX64_LOG_FILE", gameInstance.getGamePath() + "/box64.log", true);
        
        // Unity writes Player.log here
        Os.setenv("HOME", gameInstance.getGamePath(), true);
        Os.setenv("XDG_CONFIG_HOME", gameInstance.getGamePath(), true);
        // (CJK text is handled by the standalone "RimDroid CJK Font" mod — a runtime font swap that keeps
        // resources.assets untouched — not by the launcher. The old /usr/share/fonts OS-font redirect was
        // a dead end: Unity never reads OS fonts under box64.)

        // Library path for box64 to find x86_64 .so files
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), true);

        // Renderer-specific env vars
        LauncherPreferences.Renderer renderer = gameInstance.settings().getRenderer();
        // RimWorld 1.6 GLES pivot: the "rd_force_gles" marker forces the ZINK_ZFA renderer
        // (real desktop GL Core over Zink/Turnip). Unity 1.6 creates its GL context via GLX;
        // box64's wrappedlibgl.c routes glX* -> ZFA, so Unity renders GL and presents via
        // glXSwapBuffers -> zfa_swap -> ANativeWindow, bypassing the broken Vulkan present-gate.
        // Must run BEFORE the switch below so the full ZFA env (BOX64_LIBGL=libzfa etc.) applies.
        boolean forceGlesZfa = new java.io.File(gameInstance.getGamePath(), "rd_force_gles").exists();
        if (forceGlesZfa) {
            renderer = LauncherPreferences.Renderer.ZINK_ZFA;
            android.util.Log.i("RimDroid", "GameLauncher: rd_force_gles -> renderer=ZINK_ZFA (GLX->ZFA pivot for 1.6)");
            // (2026-07-10 cleanup: the earlier CALLRET=0/FORWARD=0/NODYNAREC/DYNACACHE=0 dynarec
            // suspicion was DISPROVEN — the real bug was our bridge writing through shifted args.
            // Removed: FORWARD=0 fragments dynablocks (slower + more RAM), and RAM is now the
            // limiting factor for the 1.6 loading peak on 12GB devices.)
            // CONFIRMED dynarec miscompile (interpreter run passes this point and creates 3 GL
            // contexts): SDL's statically-linked X11_GL_LoadLibrary (runtime 0x3f019457d4, fault
            // 0x3f01945a6e) reloads the Display pointer as garbage after the bridged
            // glXQueryExtension call. Surgical fix: interpret ONLY that loader (runs once at GL
            // init, zero steady-state cost); dynarec stays on everywhere else. Range covers the
            // loader + its visual-picking helper (0x3f019460fb).
            android.util.Log.i("RimDroid", "GameLauncher: rd_force_gles -> dynarec fully on (diag knobs removed)");
        }
        // The enum name maps 1:1 to the native renderer token parsed in rimdroid.c
        // (GL4ES / ZINK_ZFA / ZINK_OSMESA / SOFTPIPE).
        Os.setenv("RIMDROID_RENDERER", renderer.name(), true);  // read by rimdroid.c on init
        Os.setenv("RIMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), true);

        GpuInfo launchGpu = null;
        VulkanDriverPolicy.Decision driverDecision = null;

        switch (renderer) {
            case GL4ES:
                // Absolute path, not bare soname: libgl4es.so lives in the app's
                // private dependencies dir, which the isolated linker namespace
                // does NOT resolve by soname.  dlopen() by full path loads it
                // directly (the dir is a permitted path in the namespace).
                // Without this box64 logs "Cannot dlopen libgl4es.so" → no GL
                // backend → every GL entry point resolves to NULL → Unity crashes
                // (SIGSEGV @0x0) the moment it calls a GL function.
                Os.setenv("BOX64_LIBGL",
                    AppStorage.requireSingleton().getGl4esLibsPath() + "/libgl4es.so",
                    true);
                Os.setenv("LIBGL_ES", "3", true);   // GL4ES: use GLES3 backend → reports OpenGL 3.2
                Os.setenv("LIBGL_GL", "32", true);  // GL4ES: advertise OpenGL 3.2 (Unity requires ≥3.2)
                Os.setenv("LIBGL_MIPMAP", "1", true);
                Os.setenv("RIMDROID_GLES_MAJOR", "3", true);
                Os.setenv("RIMDROID_GLES_MINOR", "0", true);
                // SDL2 is statically linked into RimWorldLinux — GOM/ALTMY wrappers in
                // wrappedsdl2.c never activate for static SDL2.  SDL_DYNAPI forces static
                // SDL2 to dlopen our stub at the path below; box64 then intercepts the
                // SDL_DYNAPI_entry call via my2_SDL_DYNAPI_entry, which redirects
                // SDL_GL_CreateContext/MakeCurrent/SwapWindow to our EGL implementation.
                Os.setenv("SDL_DYNAMIC_API",
                    AppStorage.requireSingleton().getLibsLinuxX86Path() + "/libSDL2-2.0.so.0",
                    true);
                break;
            case ZINK_ZFA: {
                // GPU path: Zink (GL-on-Vulkan) via libzfa. (SOFTPIPE has its own
                // case below — it uses OSMesa, NOT libzfa, because the zfa frontend
                // hardcodes a Zink screen and ignores GALLIUM_DRIVER.)
                boolean soft = false;
                // Absolute path so host dlopen() in the parent finds libzfa.so
                // (the isolated namespace does not resolve it by bare soname).
                String arm64Dir = AppStorage.requireSingleton().getGl4esLibsPath();
                Os.setenv("BOX64_LIBGL", arm64Dir + "/libzfa.so", true);
                Os.setenv("GALLIUM_DRIVER", soft ? "softpipe" : "zink", true);
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "4.3", true);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "430", true);
                // DEBUG: surface Zink/Mesa shader compile/link errors + GL errors
                // to logcat, to test whether a failing core Unity shader triggers
                // the GfxDevice device-lost teardown loop (SDL_GL_DeleteContext loop).
                Os.setenv("MESA_DEBUG", "1", true);          // GL errors + warnings to stderr
                Os.setenv("MESA_GLSL", "errors", true);      // GLSL compile/link errors
                if (!soft) Os.setenv("ZINK_DEBUG", "compact", true);    // Zink-level diagnostics
                // libzfa.so exports a fixed classic-GL symbol set but is MISSING the
                // entry points for several advertised extensions (whole DSA family,
                // internalformat_query, timer_query, sparse_texture, blend_equation_
                // advanced, OES_EGL_image).  Mesa still advertises them (driver
                // supports them internally), so Unity loads those entry points via
                // SDL_GL_GetProcAddress, gets NULL (not in libzfa), then CALLS them
                // → jump to 0x0 → crash.  Disable these extensions so Unity routes
                // through the classic GL paths libzfa DOES export.
                Os.setenv("MESA_EXTENSION_OVERRIDE",
                    // Force-advertise BC/S3TC texture compression. RimWorld textures are
                    // DXT/BC; Adreno/Turnip exposes these (no-op here), but the Mali Vulkan
                    // driver lacks textureCompressionBC, so Zink hides s3tc and DXT textures
                    // load as BLACK. Forcing them on lets Mesa's built-in software BC
                    // decoder handle uploads, so textures render on Mali too.
                    "+GL_EXT_texture_compression_s3tc +GL_EXT_texture_compression_rgtc +GL_ARB_texture_compression_bptc"
                    + " -GL_ARB_direct_state_access"
                    + " -GL_ARB_internalformat_query -GL_ARB_internalformat_query2"
                    + " -GL_ARB_timer_query"
                    + " -GL_ARB_sparse_texture -GL_ARB_sparse_texture2 -GL_ARB_sparse_texture_clamp"
                    + " -GL_KHR_blend_equation_advanced -GL_KHR_blend_equation_advanced_coherent"
                    + " -GL_OES_EGL_image",
                    true);
                // Custom Turnip Vulkan ICD for Adreno (loaded by load_linker_hook
                // in the parent, before zfaCreateContext).  Bare name — resolved
                // via the rimdroid namespace search path by linkernsbypass.
                // Chosen in Settings (driver spinner); defaults to libvulkan_freedreno.so.
                // Empty string = "System" option = use the phone's own Vulkan driver
                // (rimdroid.c treats empty as NULL and skips the bundled Turnip ICD).
                // Software path needs no Vulkan ICD; GPU (Zink) path uses the chosen driver.
                String configuredDriver = soft ? "" : gameInstance.settings().getVulkanDriverSo();
                launchGpu = GpuInfo.query();
                driverDecision = VulkanDriverPolicy.resolve(
                        configuredDriver, launchGpu, forceGlesZfa);
                if (!driverDecision.configuredSo.equals(driverDecision.effectiveSo)) {
                    // Migrate stale or incompatible saved choices so Settings and later launches
                    // reflect the driver that actually worked on this device.
                    gameInstance.settings().setVulkanDriverSo(driverDecision.effectiveSo);
                }
                Log.i(TAG, "GPU '" + launchGpu.displayName() + "': " + driverDecision.reason
                        + " (configured=" + VulkanDriverPolicy.displayName(driverDecision.configuredSo)
                        + ", effective=" + VulkanDriverPolicy.displayName(driverDecision.effectiveSo) + ")");
                Os.setenv("RIMDROID_VULKAN_DRIVER_NAME", driverDecision.effectiveSo, true);
                // SDL_DYNAPI interception (same mechanism as GL4ES) so our
                // my2_SDL_GL_CreateContext/SwapWindow route to ZFA.
                Os.setenv("SDL_DYNAMIC_API",
                    AppStorage.requireSingleton().getLibsLinuxX86Path() + "/libSDL2-2.0.so.0",
                    true);
                break;
            }
            case SOFTPIPE: {
                // CPU software renderer: Mesa softpipe via OSMesa (OFFSCREEN) + a
                // manual blit to the surface (rimdroid.c). Bypasses GPU/Vulkan/EGL
                // entirely → works on ANY device (Mali/PowerVR/old Mali where Zink
                // fails or mis-renders), supports all texture formats incl. BC, but
                // is slower (CPU). Unlike Zink it does NOT go through libzfa (the zfa
                // frontend hardcodes a Zink screen and ignores GALLIUM_DRIVER), so we
                // load libOSMesa directly. libOSMesa.so is loaded by rimdroid.c via
                // the rimdroid linker namespace (so libcutils/liblog resolve); box64
                // resolves GL entry points from that handle (g_osmesa_handle).
                Os.setenv("BOX64_LIBGL", "libOSMesa.so", true);
                Os.setenv("GALLIUM_DRIVER", "softpipe", true);
                // softpipe caps at GL 3.3 (RimWorld/Unity need only 3.2 core).
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "3.3", true);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "330", true);
                // NOTE: large textures (BC7 hero-art) render BLACK on softpipe — but
                // on-device testing proved this is NOT a compression issue: disabling
                // all compression so Unity CPU-decompresses to RGBA still rendered
                // black (and forced slow emulated decompress). So we DON'T override
                // texture-compression extensions here — softpipe uses its native set
                // (faster). The black-large-texture bug is tracked separately (likely a
                // softpipe mip/sampling issue). softpipe also has the full libOSMesa, so
                // unlike ZFA we don't need the DSA/query-disable overrides either.
                // Pure CPU → no Vulkan ICD. Empty = rimdroid.c skips the Turnip inject.
                Os.setenv("RIMDROID_VULKAN_DRIVER_NAME", "", true);
                // Same SDL_DYNAPI interception as GL4ES/ZFA so the game's static SDL2
                // loads our stub and box64's my2_SDL_GL_* (CreateContext / MakeCurrent
                // / SwapWindow / GetProcAddress) route to the OSMesa softpipe path.
                Os.setenv("SDL_DYNAMIC_API",
                    AppStorage.requireSingleton().getLibsLinuxX86Path() + "/libSDL2-2.0.so.0",
                    true);
                break;
            }
            case ZINK_OSMESA:
                Os.setenv("BOX64_LIBGL", "libOSMesa.so", true);
                Os.setenv("GALLIUM_DRIVER", "zink", true);
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "4.3", true);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "430", true);
                String vulkanDriverName = LauncherPreferences.requireSingleton().getVulkanDriver().libName;
                if (vulkanDriverName != null) {
                    Os.setenv("RIMDROID_VULKAN_DRIVER_NAME", vulkanDriverName, true);
                }
                break;
        }

        // RimWorld 1.6 GLES pivot — AI-consensus one-run Zink diagnostics (brief v12). Applied
        // AFTER the renderer switch so they override ZINK_DEBUG=compact from the ZINK_ZFA case.
        // Goal: name what silently kills zink's batch state at the splash-unload present.
        if (forceGlesZfa) {
            // Verdict from the serialized-Zink diagnostic runs (brief v12): VK_ERROR_DEVICE_LOST
            // during the splash-unload frame, detected at the pre-flush glFinish (swap_phase=1),
            // on BOTH drivers, NOT caused by threading (full serialization didn't change it), NOT
            // by rebinds/ConfigureNotify (both eliminated), NOT by missing GL entry points (libzfa
            // exports all suspects incl. glFenceSync family). => Mesa 25.0.2 zink/kopper bug class
            // fixed upstream in 25.2.4/25.3 ("reset batch states on destroy", "kopper dt without
            // swapchain when pruning batch usage"). Real fix = rebuild libzfa on newer Mesa.
            // Keep only the cheap always-useful markers; the heavy ZINK_DEBUG serialization is
            // removed (caused visible flicker, changed nothing about the bug).
            // AI-brief-v13 consensus batch:
            // - NO MESA_VK_ABORT_ON_DEVICE_LOSS: it abort()s before Turnip/kgsl emit their fault
            //   report (and stdio buffering already ate the mesa.log message once). Let the process
            //   live past the device-lost so logcat captures the fault details.
            // - NO MESA_LOG_FILE: default Android mesa logging goes to logcat (unbuffered).
            // - TU_DEBUG=syncdraw: Turnip waits for the GPU after every draw/dispatch/blit → the
            //   fault becomes synchronous with the guilty submission instead of surfacing at glFinish.
            // CONTROL RUN (Codex consensus): both diagnostic overrides REMOVED — no TU_DEBUG=syncdraw,
            // no -GL_ARB_buffer_storage hide — back to the ORIGINAL death mode, with the sanity shims
            // (now incl. glBufferStorage) + external VmSize/maps polling as the only observers. The
            // buffer_storage hide reshaped the failure into a staging-BO mmap flood; measure clean.
            // Remaining crash after upload-pacing: a NATIVE zink worker thread dies at
            // libzfa+0xdc8e1c (NULL+0x30) ~768MB into the atlas bake (guest RIP parked at an
            // unrelated mprotect bridge = fault is on a mesa-internal thread). Prime suspect:
            // the shader/pipeline DISK-CACHE worker. Test: disable the mesa shader cache.
            // SYMBOLIZED (unstripped libzfa): the crash is zink_kopper_present_queue ->
            // destroy_swapchain — a race on kopper's threaded present queue while old swapchains
            // are pruned (our ROTATE_90 surface makes every present SUBOPTIMAL -> constant
            // swapchain recreation). flushsync makes presents synchronous on the caller thread:
            // no present-queue thread, no race. (Tested before, but the kgsl-3GB OOM layer was
            // masking everything then; now upload-pacing has removed that layer.)
            // FPS LEVER (2026-07-11 eve): flushsync forces a SYNCHRONOUS present on the caller
            // thread every frame — it was the cheap guard against the kopper present-queue race
            // (destroy_swapchain on the SUBOPTIMAL-recreation storm). That race is now closed at
            // the source (zink_kopper NULL-dt guard + no-SUBOPTIMAL-recreate, both in libzfa), so
            // the per-frame sync should be droppable. 1.5 runs 68 FPS vs 1.6's 25-29 with flushsync
            // on → prime FPS suspect. Gated behind a marker so we can flip it per-instance without a
            // rebuild: create "rd_flushsync" in the instance dir to force it back on if the race
            // returns. Default (no marker) = OFF = try for the FPS win.
            boolean forceFlushsync = new java.io.File(gameInstance.getGamePath(), "rd_flushsync").exists();
            if (forceFlushsync) {
                Os.setenv("ZINK_DEBUG", "flushsync", true);
                android.util.Log.i("RimDroid", "GameLauncher: rd_force_gles -> ZINK_DEBUG=flushsync (rd_flushsync marker present)");
            } else {
                android.util.Log.i("RimDroid", "GameLauncher: rd_force_gles -> flushsync OFF (FPS lever; kopper race guarded in libzfa)");
            }
            // GPU-hang hunt CLOSED (2026-07-11): the killer was Unity's runtime BC-compression
            // shader (Hidden/CompressBC: fragment shader + writeonly uimage2D imageStore) that
            // RimWorld runs on the baked atlases when textureCompression=True — Turnip/A830
            // hangs the GPU executing it (kgsl hang-class fault, no pagefault). Fixed by setting
            // <textureCompression>False</textureCompression> in RimWorld's Config/Prefs.xml.
            // The diagnostic envs (RIMDROID_GL_LOG_AFTER_SUB / RIMDROID_PACE_MB / swap-skip)
            // are gone; the box64 shims stay but are inert without them.
        }

        // Debug extras — when debug mode is on, keep box64 log modest.
        // BOX64_LOG=1 gives useful high-level markers (lib loading, GL, our RIMDROID lines).
        // We DELIBERATELY do NOT enable BOX64_DYNAREC_LOG: under Mono's Boehm GC (libmonobdwgc),
        // its atomic CMPXCHG writes hit box64's bridge region as self-modifying code, so
        // DYNAREC_LOG=1 floods thousands of "Detecting a Hotpage"/"Writting from…" lines per
        // second. The log I/O alone stalls the game into an apparent hang/crash — especially with
        // GC-heavy mods (e.g. Performance Optimizer). Keep it off so debug mode stays usable.
        if (gameInstance.settings().isDebug()) {
            Os.setenv("BOX64_LOG", "1", true);
            Os.setenv("BOX64_DYNAREC_LOG", "0", true);
        }

        // Custom env vars (KEY=VALUE pairs separated by spaces) — PER-INSTANCE (falls back to global).
        // MUST be applied after ALL defaults above — including the debug extras — so a power-user/
        // diagnostic value (e.g. BOX64_LOG=2 for an mmap trace) always wins. It used to run before the
        // debug block, which silently clobbered a user-set BOX64_LOG with the debug default of 1.
        String rawEnvVars = gameInstance.settings().getEnvVars();
        if (rawEnvVars != null && !rawEnvVars.trim().isEmpty()) {
            for (String token : rawEnvVars.trim().split("\\s+")) {
                String[] parts = token.split("=", 2);
                if (parts.length == 2) {
                    Os.setenv(parts[0].trim(), parts[1].trim(), true);
                }
            }
        }

        // Safety clamp: several "save corruption" reports trace to cargo-cult env vars (copied from a
        // third-party AI) that widen the box64 JIT race — BOX64_DYNAREC_BIGBLOCK>1, FASTNAN=1,
        // FASTROUND=1, STRONGMEM=0. In RELEASE builds we re-pin these to safe values AFTER the user
        // field, so a pasted dangerous value can't silently eat colonies. DEBUG builds leave them
        // untouched so we (devs) can still A/B these knobs.
        if (!BuildConfig.DEBUG && rawEnvVars != null) {
            String[][] clamp = {
                {"BOX64_DYNAREC_BIGBLOCK", "0"}, {"BOX64_DYNAREC_FASTNAN", "0"},
                {"BOX64_DYNAREC_FASTROUND", "0"}, {"BOX64_DYNAREC_STRONGMEM", "4"}
            };
            for (String[] kv : clamp) {
                String v = Os.getenv(kv[0]);
                if (v != null && !v.equals(kv[1])) {
                    Os.setenv(kv[0], kv[1], true);
                    postLog("Safety: ignored unsafe " + kv[0] + "=" + v + " (forced " + kv[1] + " — protects saves)");
                }
            }
        }

        // Self-describing launch header: passed to native (RIMDROID_LAUNCH_CONFIG) so it lands at
        // the top of rimdroid.log, AND mirrored to the on-screen launcher log. Makes any pasted log
        // say exactly which renderer/driver/settings produced it.
        String launchConfig = buildLaunchConfig(gameInstance, renderer, launchGpu, driverDecision);
        Os.setenv("RIMDROID_LAUNCH_CONFIG", launchConfig, true);
        for (String line : launchConfig.split("\n")) postLog(line);

        Log.i(TAG, "Launching RimWorld instance: " + gameInstance.getName());
        Log.i(TAG, "Game path: " + gameInstance.getGamePath());
        Log.i(TAG, "Renderer: " + renderer.name());
        Log.i(TAG, "BOX64_LD_LIBRARY_PATH: " + gameInstance.getLdLibraryPathForEmulation());

        // Force "C" locale — Android/Bionic has no glibc locale data files,
        // so std::locale("") with LANG=en_US.UTF-8 throws
        // "locale::facet::_S_create_c_locale name not valid" and aborts.
        Os.setenv("LANG", "C", true);
        Os.setenv("LC_ALL", "C", true);

        // No X11/Wayland on Android — use offscreen SDL2 backend so Unity doesn't
        // crash trying to connect to a display server that doesn't exist.
        // Rendering to screen is handled separately via ANativeWindow / rimdroid surface.
        // "dummy" is always compiled into SDL2 and does nothing (unlike "offscreen"
        // which may not be compiled in the game's statically-linked SDL2 build).
        Os.setenv("SDL_VIDEODRIVER", "dummy", true);
        Os.setenv("SDL_AUDIODRIVER", "dummy", true);
        // Suppress ALSA errors (no ALSA on Android)
        Os.setenv("ALSA_CONFIG_PATH", "/dev/null", true);
        // Audio goes through our libasound→AAudio shim; PulseAudio is never used. Tell box64 not to
        // even try to load/wrap libpulse / libpulse-simple, so FMOD goes straight to ALSA instead of
        // box64 logging a scary (but harmless) "Error initializing libpulse-simple" before falling back.
        Os.setenv("BOX64_NOPULSE", "1", true);

        // Create libmono.so → libmonobdwgc-2.0.so symlink if needed.
        // Unity's Linux player dlopen's "libmono.so" but MonoBleedingEdge ships
        // the library as "libmonobdwgc-2.0.so".
        setupMonoSymlink(gameInstance.getGamePath());

        // RimWorld 1.6 X11 runtime (spike, marker-driven like rd_batchmode): boot the
        // in-process X server (ported from Winlator) and point the guest at it. The guest's
        // glibc libX11 connects to the hardcoded "/tmp/.X11-unix/X0"; box64's connect()
        // redirect maps that to RIMDROID_X11_SOCKET_DIR. See memory rimworld_16_port.
        boolean x11Test = new java.io.File(gameInstance.getGamePath(), "rd_x11_test").exists();
        if (x11Test || new java.io.File(gameInstance.getGamePath(), "rd_x11").exists()) {
            // Unity 2022 uses Vulkan directly on the X11 route. Do not let the legacy
            // ZFA/GL renderer bind its own Kopper swapchain to the same ANativeWindow first.
            // EXCEPTION — the rd_force_gles pivot: we WANT ZFA to bind its swapchain, because
            // Unity 1.6 renders via GLX->ZFA (not direct Vulkan). So skip DIRECT_VULKAN then.
            if (!forceGlesZfa) {
                Os.setenv("RIMDROID_DIRECT_VULKAN", "1", true);
            } else {
                Os.unsetenv("RIMDROID_DIRECT_VULKAN");
                android.util.Log.i("RimDroid", "GameLauncher: rd_force_gles -> DIRECT_VULKAN OFF, ZFA binds swapchain");
            }
            // Match the X screen to the actual Android buffer size (fallback 1280x720 if the
            // surface isn't up yet) — Unity requests fullscreen at "desktop" size, and any
            // mismatch with our -screen-width args causes an endless resize/swapchain loop.
            // Wait here (COMMON launch path, not just autolaunch) until the surface size has
            // been stable for 500 ms — surfaceChanged can fire twice at startup.
            try {
                int lw = 0, lh = 0; long stableSince = 0;
                for (int i = 0; i < 100; ++i) {
                    int w = lastSurfaceWidth, h = lastSurfaceHeight;
                    if (w > 0 && w == lw && h == lh) {
                        if (stableSince == 0) stableSince = System.currentTimeMillis();
                        else if (System.currentTimeMillis() - stableSince >= 500) break;
                    } else { lw = w; lh = h; stableSince = 0; }
                    Thread.sleep(50);
                }
            } catch (InterruptedException ignored) {}
            int xw = lastSurfaceWidth  > 0 ? lastSurfaceWidth  : 1280;
            int xh = lastSurfaceHeight > 0 ? lastSurfaceHeight : 720;
            String sock = com.rimdroid.xserver.XServerRunner.start(
                    gameInstance.getGamePath(), xw, xh);
            Os.setenv("DISPLAY", ":0", true);
            Os.setenv("RIMDROID_X11_SOCKET_DIR", new java.io.File(sock).getParent(), true);
            // Unity 2022's STATIC SDL (no dynapi) must use its x11 video driver against OUR
            // X server — override the unconditional "dummy" set above (dummy leaves SDL video
            // uninitialised in 2022 → "Error getting num native displays" → crash on an empty
            // displays array).
            Os.setenv("SDL_VIDEODRIVER", "x11", true);
            // Force SDL to use our root visual by id (bypasses XMatchVisualInfo, which was failing to
            // match our depth-32 TrueColor visual → SDL added 0 displays → Unity crashed). See
            // memory rimworld_16_port.
            int visualId = com.rimdroid.xserver.XServerRunner.getRootVisualId();
            Os.setenv("SDL_VIDEO_X11_VISUALID", "0x" + Integer.toHexString(visualId), true);
            postLog("X server started: " + sock + " (visualid=0x" + Integer.toHexString(visualId) + ")");
            if (x11Test) {
                // M1 smoke test: run a guest x86_64 X client instead of the game. The marker
                // file's first line names the binary in deps/linux-x86_64 (empty = xdpyinfo).
                String tool = "xdpyinfo";
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                        new java.io.File(gameInstance.getGamePath(), "rd_x11_test")))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) tool = line.trim();
                } catch (Exception ignored) {}
                String path = new java.io.File(
                        AppStorage.requireSingleton().getLibsLinuxX86Path(), tool).getAbsolutePath();
                Os.setenv("RIMDROID_EXEC", path, true);
                // Reference SDL clients must use the REAL (emulated Debian) libSDL2 from the
                // instance dir, not our wrapped 1.5 stub — box64 force-wraps libSDL2 otherwise.
                Os.setenv("BOX64_EMULATED_LIBS", "libSDL2-2.0.so.0:libdrm.so.2:libgbm.so.1"
                        + ":libwayland-client.so.0:libwayland-cursor.so.0:libwayland-egl.so.1"
                        + ":libdecor-0.so.0:libsamplerate.so.0:libxkbcommon.so.0:libxkbcommon-x11.so.0"
                        + ":libpulse.so.0:libasound.so.2:libffi.so.8:libwayland-server.so.0:libexpat.so.1",
                        true);  // NOT libpulse/asound/dbus — box64 can't wrap them on Android (no native)
                Os.setenv("BOX64_LOG", "1", true);   // verbose: name the fatal needed-lib precisely
                Os.setenv("BOX64_ALLOWMISSINGLIBS", "1", true);  // skip Android-absent libpulse etc.
                Os.setenv("BOX64_NOPULSE", "0", true);   // let our x86_64 pulse STUB load (global default=1 dummies it)
                postLog("X11 SMOKE TEST: exec " + path);
            } else {
                Os.unsetenv("RIMDROID_EXEC");
            }
        } else {
            Os.unsetenv("RIMDROID_EXEC");
            Os.unsetenv("RIMDROID_DIRECT_VULKAN");
        }

        postLog("Launching " + gameInstance.getName() + " [" + renderer.name() + "]...");
        postLog("Path: " + gameInstance.getGamePath());

        // Start reading logcat
        startLogcatReader();

        // Initialize the native window surface
        initRimDroidWindow();
        // Passing libraries x86_64 path before start
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), true);

        if (USE_STANDALONE_EXEC) {
            // EXEC PATH (Milestone 1): run box64+RimWorld as a FRESH exec'd process
            // (clean address space, no fork, fresh binder → fixes GPU-after-fork).
            // Surface/GPU presentation NOT wired yet — this sub-step (1b) only
            // validates that RimWorld runs in the clean no-fork process and reaches
            // renderer detection.  Log goes to <game>/rimdroid_game.log.
            postLog("Launching via STANDALONE EXEC (no-fork process)...");
            Log.i(TAG, "Launching via execStandaloneGame (no-fork)");
            // 1.6/X11+Vulkan: multithreaded rendering REQUIRED — with -force-gfx-direct the
            // render runs on the main thread, so during RimWorld's blocking LongEventHandler
            // load no frame ever completes/presents and Unity's 2MB device-memory pool chunks
            // are never recycled → "Vulkan - Out of memory" at ~3GB. The gfx thread presents
            // between load steps like on desktop. 1.5 (SDL/GL path) keeps gfx-direct.
            boolean rdX11 = new java.io.File(gameInstance.getGamePath(), "rd_x11").exists();
            int code = execStandaloneGame(
                    gameInstance.getGamePath(),
                    gameInstance.getNativeLibraryPath(),
                    rdX11 ? null : "-force-gfx-direct");
            postLog("Standalone exec exited, code=" + code);
            Log.i(TAG, "execStandaloneGame returned code=" + code);
        } else {
            // Legacy JNI in-process + fork path (known to crash at first GPU
            // texture upload due to GPU-after-fork; kept for comparison).
            startGame(
                    gameInstance.getGamePath(),
                    gameInstance.getNativeLibraryPath(),
                    gameInstance.getArgs()
            );
        }

        postLog("Game process ended.");
        stopLogcatReader();
    }

    // Toggle: true = exec'd fresh-process path (bare process, no GPU framework —
    // hit the bare-process GPU wall). false = JNI in-process path, which now
    // auto-detects relocatable games (UnityPlayer.so → no fork, in-process) vs
    // monolithic (fork). Use false for RimWorld 1.5+ (relocatable).
    private static final boolean USE_STANDALONE_EXEC = false;

    // ---- Logcat reader ------------------------------------------------------

    private static void startLogcatReader() {
        stopLogcatReader();
        logcatReader = new LogcatReader(line -> postLog(line));
        logcatReader.start();
    }

    private static void stopLogcatReader() {
        if (logcatReader != null) {
            logcatReader.stop();
            logcatReader = null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a libmono.so → libmonobdwgc-2.0.so symlink in the Mono x86_64 dir if needed.
     * Unity's Linux player dlopen's "libmono.so", but MonoBleedingEdge ships the library
     * as "libmonobdwgc-2.0.so" — the symlink bridges the gap.
     */
    private static void setupMonoSymlink(String gamePath) {
        String monoDir = gamePath + "/RimWorldLinux_Data/MonoBleedingEdge/x86_64";
        String libmonoPath    = monoDir + "/libmono.so";
        String libmonobdwPath = monoDir + "/libmonobdwgc-2.0.so";

        File target = new File(libmonobdwPath);
        File link   = new File(libmonoPath);

        if (!target.exists()) {
            Log.w(TAG, "setupMonoSymlink: target not found: " + libmonobdwPath);
            return;
        }
        if (link.exists()) {
            Log.d(TAG, "setupMonoSymlink: libmono.so already exists, skipping");
            return;
        }
        try {
            Os.symlink("libmonobdwgc-2.0.so", libmonoPath);
            Log.i(TAG, "setupMonoSymlink: created libmono.so → libmonobdwgc-2.0.so");
            postLog("Mono symlink created: libmono.so → libmonobdwgc-2.0.so");
        } catch (ErrnoException e) {
            Log.w(TAG, "setupMonoSymlink: failed to create symlink: " + e.getMessage());
            postLog("Warning: could not create libmono.so symlink: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Native methods
    // -------------------------------------------------------------------------

    public static native int initRimDroidWindow();
    public static native void destroyRimDroidWindow();
    // RimDroid 1.6/X11: remember the buffer size so the X server screen matches the surface —
    // a mismatched screen (hardcoded 1280x720) made Unity loop resize→fullscreen→swapchain
    // forever until lmkd killed the process (PSS grew to 8 GB). See memory rimworld_16_port.
    public static volatile int lastSurfaceWidth = 0;
    public static volatile int lastSurfaceHeight = 0;

    public static int setSurfaceTracked(Surface surface, int width, int height) {
        lastSurfaceWidth = width;
        lastSurfaceHeight = height;
        return setSurface(surface, width, height);
    }

    public static native int setSurface(Surface surface, int width, int height);
    public static native void destroySurface();

    /** Software-renderer (OSMesa + softpipe) smoke test: render a test frame on the CPU and
     *  blit it to the current surface. Returns 0 on success. Requires a live surface
     *  (call after setSurface). osmesaLibPath = absolute path to libOSMesa.so. */
    public static native int nativeOsmesaSmokeTest(String osmesaLibPath);
    static native void startGame(String gameDirPath, String libraryDirPath, String[] args);

    /**
     * Launch box64+RimWorld as a FRESH exec'd process (fork+execve in native).
     * Returns the child's exit code. Blocks until the game process ends.
     */
    static native int execStandaloneGame(String gameDirPath, String libraryDirPath, String extraArg);
}
