package com.rimdroid.game;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;

import com.rimdroid.AppStorage;
import com.rimdroid.InstanceSettings;

import java.io.File;
import java.util.List;

/**
 * Native ARM64 Mono (experimental): the x86_64 UnityPlayer keeps running under box64, but RimWorld's
 * managed code runs on a native ARM64 build of Unity's Mono. box64 routes the game's
 * libmonobdwgc-2.0.so to its wrapper when RIMDROID_NATIVE_MONO_PATH is set.
 *
 * The runtime is packaged as ordinary APK native libraries from app/src/main/jniLibs/arm64-v8a, so Android
 * extracts it next to librimdroid.so: libmonobdwgc-2.0.so (Unity's Mono fork, commit c11bc9adba, built for
 * Android ARM64 and stripped), libmono-native.so from the same build, and libsteam_api.so, a stub whose
 * Steamworks functions all return 0. An APK built without them simply does not offer the switch.
 */
public final class NativeMono {
    private static final String RUNTIME_LIB = "libmonobdwgc-2.0.so";

    private NativeMono() {}

    /** Absolute path of the packaged ARM64 Mono, or null when this APK was built without it. */
    public static String runtimePath() {
        File lib = new File(AppStorage.requireSingleton().getLibraryPath(), RUNTIME_LIB);
        return lib.isFile() ? lib.getAbsolutePath() : null;
    }

    /**
     * Only RimWorld 1.6 (Unity 2022.3, the "rd_x11" runtime) is supported: the bridge's internal call
     * thunks are generated for that Unity version, and 1.5's Unity 2019 Mono is a different runtime.
     */
    public static boolean isSupported(GameInstance instance) {
        return instance != null
                && runtimePath() != null
                && new File(instance.getGamePath(), "rd_x11").exists();
    }

    /**
     * Safety net. The native runtime is on by default, so a player whose phone or mods do not get along
     * with it may not even know the switch exists. After {@link #FAILURES_TO_ASK} early crashes in a row
     * the launcher offers to turn it off (LauncherFragment).
     */
    public static final int FAILURES_TO_ASK = 2;

    /** A crash later than this after the launch is a crash during play, not a failed launch. */
    private static final long EARLY_CRASH_MS = 10 * 60 * 1000L;

    /** Called by GameLauncher when a launch really uses the native runtime (or really does not). */
    public static void noteLaunch(InstanceSettings settings, boolean nativeRuntime) {
        settings.setNativeMonoLaunchTime(nativeRuntime ? System.currentTimeMillis() : 0L);
    }

    /**
     * Settles the previous native-runtime launch of this instance against Android's record of how our
     * process died, and returns the number of early crashes in a row. A crash (native crash, fatal signal,
     * our fatal-signal handler's exit code, or an ANR kill) within {@link #EARLY_CRASH_MS} of the launch
     * counts; any other ending (the player quitting or swiping the app away) resets the count.
     */
    public static int settlePreviousLaunch(Context context, InstanceSettings settings) {
        long launchedAt = settings.getNativeMonoLaunchTime();
        int failures = settings.getNativeMonoFailures();
        if (launchedAt <= 0) return failures;
        settings.setNativeMonoLaunchTime(0L);
        ApplicationExitInfo end = null;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16);
            // Newest first: the oldest death after the launch is the one that ended that session.
            for (ApplicationExitInfo e : exits) {
                if (e.getTimestamp() < launchedAt) break;
                if (context.getPackageName().equals(e.getProcessName())) end = e;
            }
        } catch (Exception ignored) {
            return failures;   // no record available: leave the count as it was
        }
        if (end == null) return failures;
        boolean early = end.getTimestamp() - launchedAt <= EARLY_CRASH_MS;
        failures = early && isCrash(end) ? failures + 1 : 0;
        settings.setNativeMonoFailures(failures);
        return failures;
    }

    private static boolean isCrash(ApplicationExitInfo e) {
        switch (e.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
                return true;
            case ApplicationExitInfo.REASON_SIGNALED:
                switch (e.getStatus()) {
                    case 4:   // SIGILL
                    case 5:   // SIGTRAP
                    case 6:   // SIGABRT
                    case 7:   // SIGBUS
                    case 8:   // SIGFPE
                    case 11:  // SIGSEGV
                    case 31:  // SIGSYS
                        return true;
                    default:
                        return false;   // e.g. the game's normal quit
                }
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return e.getStatus() == 139;   // rimdroid.c handle_fatal_signal
            default:
                return false;
        }
    }
}
