package com.rimdroid;

import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import android.util.Log;

import com.rimdroid.game.GameInstance;

public class GameLauncher {

    private static final String TAG = "RimDroid/GameLauncher";

    // Callback для передачи лог-строк в UI
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
        Os.setenv("BOX64_LOG", "1", true);
        Os.setenv("BOX64_SHOWBT", "1", true);
        Os.setenv("BOX64_DYNAREC", "1", true);
        Os.setenv("BOX64_DYNAREC_BIGBLOCK", "0", true);  // 0 для Unity/Mono JIT
        Os.setenv("BOX64_DYNAREC_SAFEFLAGS", "1", true);
        Os.setenv("BOX64_DYNAREC_STRONGMEM", "1", true); // Важно для Unity
        Os.setenv("BOX64_PREFER_EMULATED", "1", true);   // Использовать x86_64 glibc

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
                Os.setenv("BOX64_LIBGL", "libgl4es.so", true);
                Os.setenv("LIBGL_ES", "2", true);
                Os.setenv("LIBGL_MIPMAP", "1", true);
                Os.setenv("RIMDROID_GLES_MAJOR", "2", true);
                Os.setenv("RIMDROID_GLES_MINOR", "0", true);
                break;
            case ZINK_ZFA:
                Os.setenv("BOX64_LIBGL", "libzfa.so", true);
                Os.setenv("GALLIUM_DRIVER", "zink", true);
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "4.3", true);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "430", true);
                break;
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

        // Debug extras
        if (LauncherPreferences.requireSingleton().isDebug()) {
            Os.setenv("BOX64_LOG", "3", true);
        }

        Log.i(TAG, "Launching RimWorld instance: " + gameInstance.getName());
        Log.i(TAG, "Game path: " + gameInstance.getGamePath());
        Log.i(TAG, "Renderer: " + renderer.name());
        Log.i(TAG, "BOX64_LD_LIBRARY_PATH: " + gameInstance.getLdLibraryPathForEmulation());

        postLog("Launching " + gameInstance.getName() + " [" + renderer.name() + "]...");
        postLog("Path: " + gameInstance.getGamePath());

        // Запускаем чтение logcat
        startLogcatReader();

        // Initialize the native window surface
        initRimDroidWindow();
        // Passing libraries x86_64 path before start
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), true);
        
        // Launch: box64 runs RimWorldLinux ELF directly (no JVM needed)
        startGame(
                gameInstance.getGamePath(),
                gameInstance.getNativeLibraryPath(),
                gameInstance.getArgs()
        );

        postLog("Game process ended.");
        stopLogcatReader();
    }

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
    // Native methods
    // -------------------------------------------------------------------------

    public static native int initRimDroidWindow();
    public static native void destroyRimDroidWindow();
    public static native int setSurface(Surface surface, int width, int height);
    public static native void destroySurface();
    static native void startGame(String gameDirPath, String libraryDirPath, String[] args);
}
