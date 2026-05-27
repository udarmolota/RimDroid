package com.rimdroid;

import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import android.util.Log;

import com.rimdroid.game.GameInstance;

public class GameLauncher {

    private static final String TAG = "RimDroid/GameLauncher";

    public static void launch(GameInstance gameInstance) throws ErrnoException {

        // --- Box64 tuning ---
        Os.setenv("BOX64_LOG", "1", false);
        Os.setenv("BOX64_SHOWBT", "1", false);
        Os.setenv("BOX64_DYNAREC", "1", false);
        Os.setenv("BOX64_DYNAREC_BIGBLOCK", "1", false);
        Os.setenv("BOX64_DYNAREC_SAFEFLAGS", "1", false);

        // Library path for box64 to find x86_64 .so files
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), false);

        // Renderer selection — read by rimdroid.c on init
        Os.setenv("RIMDROID_RENDERER", LauncherPreferences.requireSingleton().getRenderer().name(), false);
        Os.setenv("RIMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), false);

        // Renderer-specific env vars
        LauncherPreferences.Renderer renderer = LauncherPreferences.requireSingleton().getRenderer();
        switch (renderer) {
            case GL4ES:
                Os.setenv("BOX64_LIBGL", "libgl4es.so", false);
                Os.setenv("LIBGL_ES", "2", false);
                Os.setenv("LIBGL_MIPMAP", "1", false);
                Os.setenv("RIMDROID_GLES_MAJOR", "2", false);
                Os.setenv("RIMDROID_GLES_MINOR", "0", false);
                break;
            case ZINK_ZFA:
                Os.setenv("BOX64_LIBGL", "libzfa.so", false);
                Os.setenv("GALLIUM_DRIVER", "zink", false);
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "4.3", false);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "430", false);
                break;
            case ZINK_OSMESA:
                Os.setenv("BOX64_LIBGL", "libOSMesa.so", false);
                Os.setenv("GALLIUM_DRIVER", "zink", false);
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "4.3", false);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "430", false);
                // Vulkan driver
                String vulkanDriverName = LauncherPreferences.requireSingleton().getVulkanDriver().libName;
                if (vulkanDriverName != null) {
                    Os.setenv("RIMDROID_VULKAN_DRIVER_NAME", vulkanDriverName, false);
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

        // Debug extras
        if (LauncherPreferences.requireSingleton().isDebug()) {
            Os.setenv("BOX64_LOG", "3", true);
        }

        Log.i(TAG, "Launching RimWorld instance: " + gameInstance.getName());
        Log.i(TAG, "Game path: " + gameInstance.getGamePath());
        Log.i(TAG, "Renderer: " + renderer.name());
        Log.i(TAG, "BOX64_LD_LIBRARY_PATH: " + gameInstance.getLdLibraryPathForEmulation());

        // Initialize the native window surface (GLFW replacement)
        initRimDroidWindow();

        // Launch: box64 runs RimWorldLinux ELF directly (no JVM needed)
        startGame(
                gameInstance.getGamePath(),
                gameInstance.getNativeLibraryPath(),
                gameInstance.getArgs()
        );
    }

    // -------------------------------------------------------------------------
    // Native methods — implemented in rimdroid_jni.c
    // -------------------------------------------------------------------------

    /** Called before startGame — initialises renderer/surface state */
    public static native int initRimDroidWindow();

    /** Called on Activity destroy */
    public static native void destroyRimDroidWindow();

    /** Called when SurfaceView surface is ready */
    public static native int setSurface(Surface surface, int width, int height);

    /** Called when surface is destroyed */
    public static native void destroySurface();

    /**
     * Core launch entry point.
     * Implemented in rimdroid_jni.c → rimdroid.c → zomdroid_start_game equivalent.
     *
     * @param gameDirPath     absolute path to RimWorld instance folder
     * @param libraryDirPath  colon-separated ARM64 native lib paths
     * @param args            args passed to RimWorldLinux (usually empty)
     */
    static native void startGame(String gameDirPath, String libraryDirPath, String[] args);
}
