package com.rimdroid;

public class C {

    public static final String STORAGE_PROVIDER_AUTHORITY = "com.rimdroid.STORAGE_PROVIDER_AUTHORITY";

    public static class deps {
        public static final String ROOT = "dependencies";
        // Content revision of assets/bundles/libs.tar.xz. BUMP whenever the bundle changes so existing
        // installs (which already have areDependenciesInstalled=true) re-extract it on next app open and
        // pick up added/updated libs — a plain boolean flag would leave updaters on the old libs.
        //   v1 = original bundle (renderer + 7 basic x86_64 libs)
        //   v2 = + 24 Debian x86_64 X11 client libs (libX11/xcb/Xrandr…) for RimWorld 1.6 SDL video
        //   v3 = libzfa.so rebuilt with the NULL-resource guards in our ZFA frontend (fixes the
        //        Adreno 610 SIGSEGV in tc_flush_resource before the first frame)
        public static final int BUNDLE_VERSION = 3;
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

    public static class mime {
        /** SAF filter for installing a game / DLC / mod: ZIP only, deliberately. A bare GOG {@code .sh}
         *  installer is NOT offered — its reported type varies wildly by document provider and most
         *  file explorers won't hand one over at all, so the supported workflow is to WRAP the .sh in
         *  a zip (GogInstallerExtractor unpacks .sh files found inside). octet-stream is included
         *  because some providers report zips as that. */
        public static final String[] GAME_ARCHIVE = {
            "application/zip", "application/x-zip-compressed", "application/octet-stream",
        };
    }

    public static class shprefs {
        public static final String NAME = "com.rimdroid.PREFS";

        public static class keys {
            public static final String LAUNCHER_VERSION          = "launcherVersion";
            public static final String GAME_INSTANCES            = "gameInstances";
            public static final String LAUNCHER_PREFS            = "launcherPrefs";
            public static final String ARE_DEPENDENCIES_INSTALLED = "areDependenciesInstalled";
            public static final String DEPENDENCIES_BUNDLE_VERSION = "dependenciesBundleVersion";
            public static final String IS_LEGAL_NOTICE_ACCEPTED  = "isLegalNoticeAccepted";
        }
    }

    public static class files {
        public static final String RIMWORLD_BIN = "RimWorldLinux";
    }
}
