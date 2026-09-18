package com.rimdroid.game;

import com.rimdroid.AppStorage;

import java.io.File;

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
}
