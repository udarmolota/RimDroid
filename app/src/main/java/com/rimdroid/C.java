package com.rimdroid;

public class C {

    public static final String STORAGE_PROVIDER_AUTHORITY = "com.rimdroid.STORAGE_PROVIDER_AUTHORITY";

    public static class deps {
        public static final String ROOT = "dependencies";
        // x86_64 game libs (libgcc_s.so.1, libjniwrapper.so, etc.)
        public static final String LIBS_LINUX_X86_64 = ROOT + "/linux-x86_64";
        // Android ARM64 renderer libs — all in one flat directory
        public static final String LIBS_ANDROID_ARM64 = ROOT + "/android-arm64-v8a";
        public static final String LIBS_GL4ES  = LIBS_ANDROID_ARM64;
        public static final String LIBS_ZINK   = LIBS_ANDROID_ARM64;
        // Custom Vulkan driver (user-supplied)
        public static final String CUSTOM_DRIVER_FILENAME = "custom_driver.so";
        public static final String CUSTOM_DRIVER = LIBS_ANDROID_ARM64 + "/" + CUSTOM_DRIVER_FILENAME;
    }

    public static class assets {
        public static final String BUNDLES = "bundles";
        public static final String BUNDLES_LIBS = BUNDLES + "/libs.tar.xz";
    }

    public static class shprefs {
        public static final String NAME = "com.rimdroid.PREFS";

        public static class keys {
            public static final String LAUNCHER_VERSION          = "launcherVersion";
            public static final String GAME_INSTANCES            = "gameInstances";
            public static final String LAUNCHER_PREFS            = "launcherPrefs";
            public static final String ARE_DEPENDENCIES_INSTALLED = "areDependenciesInstalled";
            public static final String IS_LEGAL_NOTICE_ACCEPTED  = "isLegalNoticeAccepted";
        }
    }

    public static class files {
        public static final String RIMWORLD_BIN = "RimWorldLinux";
    }
}
