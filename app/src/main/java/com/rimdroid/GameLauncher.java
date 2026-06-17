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

    /**
     * Build the human-readable "launch config" block recorded at the top of rimdroid.log (and shown
     * in the launcher log). Lists the per-instance settings + the key box64 knobs this run uses.
     * GL_RENDERER/GL_VERSION are added later by the native side once GL initialises.
     */
    /** True if the RimDroidSound (PCM audio) mod is present in this instance's Mods folder. Matched by
     *  folder name (contains "rimdroidsound") or by About.xml packageId "rimdroid.sound" (in case the
     *  folder was renamed). Used to gate audio so the un-modded screech never plays. */
    private static boolean isSoundModInstalled(GameInstance gi) {
        java.io.File[] dirs = new java.io.File(gi.getGamePath(), "Mods").listFiles();
        if (dirs == null) return false;
        for (java.io.File d : dirs) {
            if (!d.isDirectory()) continue;
            if (d.getName().toLowerCase().contains("rimdroidsound")) return true;
            java.io.File about = new java.io.File(d, "About/About.xml");
            if (about.isFile() && fileContains(about, "rimdroid.sound")) return true;
        }
        return false;
    }

    private static boolean fileContains(java.io.File f, String needle) {
        String lneedle = needle.toLowerCase();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null)
                if (line.toLowerCase().contains(lneedle)) return true;
        } catch (Exception ignore) {}
        return false;
    }

    private static String buildLaunchConfig(GameInstance gi) {
        InstanceSettings s = gi.settings();
        String so = s.getVulkanDriverSo();
        String soShown = (so == null || so.isEmpty()) ? "System (phone driver)" : so;
        String driverLabel = LauncherPreferences.VULKAN_DRIVERS.get(s.getVulkanDriverIndex()).label;
        boolean interp = s.isInterpreter();
        return "=== RimDroid launch config ===\n"
            + "instance      : " + gi.getName() + "\n"
            + "app version   : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
            + "renderer      : " + s.getRenderer().name() + "\n"
            + "vulkan driver : " + driverLabel + "  [" + soShown + "]\n"
            + "render scale  : " + s.getRenderScalePercent() + "%\n"
            + "debug         : " + (s.isDebug() ? "ON" : "off") + "\n"
            + "interpreter   : " + (interp ? "ON (dynarec OFF)" : "off") + "\n"
            + "box64         : DYNAREC=" + (interp ? "0" : "1")
                + " STRONGMEM=4 BIGBLOCK=0 SAFEFLAGS=1 WEAKBARRIER=1\n"
            + "extra env     : " + envFieldReport(s) + "\n"
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

        // --- Audio (experimental, GLOBAL toggle, default OFF) ---
        // Load the libasound→AAudio shim only when audio is enabled, right before launch, so the
        // toggle takes effect on the very next game start (no app restart). Its DT_SONAME is
        // "libasound.so.2", so loading it here registers it under that soname before the guest's FMOD
        // dlopen("libasound.so.2") runs → sound via AAudio. OFF (default) = FMOD finds no audio device
        // = clean silence (FMOD output under box64 is currently garbled noise). Best-effort.
        // Gate on the RimDroidSound mod being present in THIS instance: box64's Vorbis/codec decode is
        // broken, so RimWorld's stock audio plays as a screech. The RimDroidSound mod swaps those clips
        // for plain PCM (clean). Without the mod, enabling sound = screech — so if the mod isn't there,
        // we keep the shim unloaded (silence) even when the toggle is on. Sound effectively turns on
        // together with the mod; no screech on a first launch before the mod is added.
        if (LauncherPreferences.requireSingleton().isAudioEnabled()) {
            if (!isSoundModInstalled(gameInstance)) {
                postLog("Audio: RimDroidSound mod not installed in this instance — sound stays off "
                        + "until you add it (this avoids the un-modded screech).");
            } else {
                try {
                    System.loadLibrary("asoundshim");
                    postLog("Audio: libasound→AAudio shim loaded (experimental)");
                } catch (Throwable t) {
                    Log.e(TAG, "asound shim load failed; game will be silent", t);
                }
            }
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
        // BOX64_PREFER_EMULATED intentionally NOT set:
        // with prefer_emulated=1 box64 skips initWrappedLib for all non-essential libs,
        // including SDL2 — our my2_SDL_DYNAPI_entry never fires.
        // glibc/libm/libpthread are essential and stay wrapped regardless.

        // box64 log
        Os.setenv("BOX64_LOG_FILE", gameInstance.getGamePath() + "/box64.log", true);
        
        // Unity writes Player.log here
        Os.setenv("HOME", gameInstance.getGamePath(), true);
        Os.setenv("XDG_CONFIG_HOME", gameInstance.getGamePath(), true);

        // Library path for box64 to find x86_64 .so files
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), true);

        // Renderer-specific env vars
        LauncherPreferences.Renderer renderer = gameInstance.settings().getRenderer();
        // The enum name maps 1:1 to the native renderer token parsed in rimdroid.c
        // (GL4ES / ZINK_ZFA / ZINK_OSMESA / SOFTPIPE).
        Os.setenv("RIMDROID_RENDERER", renderer.name(), true);  // read by rimdroid.c on init
        Os.setenv("RIMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), true);

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
                String vkDriver = soft ? "" : gameInstance.settings().getVulkanDriverSo();
                Os.setenv("RIMDROID_VULKAN_DRIVER_NAME", vkDriver == null ? "" : vkDriver, true);
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
        String launchConfig = buildLaunchConfig(gameInstance);
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
            int code = execStandaloneGame(
                    gameInstance.getGamePath(),
                    gameInstance.getNativeLibraryPath(),
                    "-force-gfx-direct");
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
