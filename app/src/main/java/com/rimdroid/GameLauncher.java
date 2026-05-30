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

    public static void launch(GameInstance gameInstance) throws ErrnoException {

        // --- Box64 tuning ---
        // BOX64_LOG: 0 normally (verbose tracing = gigabyte logs). Raise to 1-2
        // only for targeted call-sequence tracing.
        Os.setenv("BOX64_LOG", "0", true);
        Os.setenv("BOX64_SHOWBT", "1", true);
        Os.setenv("BOX64_DYNAREC", "1", true);
        Os.setenv("BOX64_DYNAREC_BIGBLOCK", "0", true);  // 0 for Unity/Mono JIT
        Os.setenv("BOX64_DYNAREC_SAFEFLAGS", "1", true);
        Os.setenv("BOX64_DYNAREC_STRONGMEM", "3", true);    // TSO full emulation — fix memory ordering SIGSEGV
        Os.setenv("BOX64_DYNAREC_WEAKBARRIER", "1", true); // Extra barriers in dynarec
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

        // Renderer selection — read by rimdroid.c on init
        Os.setenv("RIMDROID_RENDERER", LauncherPreferences.requireSingleton().getRenderer().name(), true);
        Os.setenv("RIMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), true);

        // Renderer-specific env vars
        LauncherPreferences.Renderer renderer = LauncherPreferences.requireSingleton().getRenderer();
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
                // Absolute path so host dlopen() in the parent finds libzfa.so
                // (the isolated namespace does not resolve it by bare soname).
                String arm64Dir = AppStorage.requireSingleton().getGl4esLibsPath();
                Os.setenv("BOX64_LIBGL", arm64Dir + "/libzfa.so", true);
                Os.setenv("GALLIUM_DRIVER", "zink", true);
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "4.3", true);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "430", true);
                // DEBUG: surface Zink/Mesa shader compile/link errors + GL errors
                // to logcat, to test whether a failing core Unity shader triggers
                // the GfxDevice device-lost teardown loop (SDL_GL_DeleteContext loop).
                Os.setenv("MESA_DEBUG", "1", true);          // GL errors + warnings to stderr
                Os.setenv("MESA_GLSL", "errors", true);      // GLSL compile/link errors
                Os.setenv("ZINK_DEBUG", "compact", true);    // Zink-level diagnostics
                // libzfa.so exports a fixed classic-GL symbol set but is MISSING the
                // entry points for several advertised extensions (whole DSA family,
                // internalformat_query, timer_query, sparse_texture, blend_equation_
                // advanced, OES_EGL_image).  Mesa still advertises them (driver
                // supports them internally), so Unity loads those entry points via
                // SDL_GL_GetProcAddress, gets NULL (not in libzfa), then CALLS them
                // → jump to 0x0 → crash.  Disable these extensions so Unity routes
                // through the classic GL paths libzfa DOES export.
                Os.setenv("MESA_EXTENSION_OVERRIDE",
                    "-GL_ARB_direct_state_access"
                    + " -GL_ARB_internalformat_query -GL_ARB_internalformat_query2"
                    + " -GL_ARB_timer_query"
                    + " -GL_ARB_sparse_texture -GL_ARB_sparse_texture2 -GL_ARB_sparse_texture_clamp"
                    + " -GL_KHR_blend_equation_advanced -GL_KHR_blend_equation_advanced_coherent"
                    + " -GL_OES_EGL_image",
                    true);
                // Custom Turnip Vulkan ICD for Adreno (loaded by load_linker_hook
                // in the parent, before zfaCreateContext).  Bare name — resolved
                // via the rimdroid namespace search path by linkernsbypass.
                Os.setenv("RIMDROID_VULKAN_DRIVER_NAME", "libvulkan_freedreno.v25.so", true);
                // SDL_DYNAPI interception (same mechanism as GL4ES) so our
                // my2_SDL_GL_CreateContext/SwapWindow route to ZFA.
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

        // Custom env vars from settings (KEY=VALUE pairs separated by spaces)
        String rawEnvVars = LauncherPreferences.requireSingleton().getEnvVars();
        if (rawEnvVars != null && !rawEnvVars.trim().isEmpty()) {
            for (String token : rawEnvVars.trim().split("\\s+")) {
                String[] parts = token.split("=", 2);
                if (parts.length == 2) {
                    Os.setenv(parts[0].trim(), parts[1].trim(), true);
                }
            }
        }

        // Debug extras — when debug mode is on, keep box64 log modest.
        // LOG=1 (not 3) + dynarec error reporting; LOG=3 generates gigabyte logs.
        if (LauncherPreferences.requireSingleton().isDebug()) {
            Os.setenv("BOX64_LOG", "1", true);
            Os.setenv("BOX64_DYNAREC_LOG", "1", true); // dynarec errors/illegal instructions
        }

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
    static native void startGame(String gameDirPath, String libraryDirPath, String[] args);

    /**
     * Launch box64+RimWorld as a FRESH exec'd process (fork+execve in native).
     * Returns the child's exit code. Blocks until the game process ends.
     */
    static native int execStandaloneGame(String gameDirPath, String libraryDirPath, String extraArg);
}
