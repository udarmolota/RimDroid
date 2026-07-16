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
        public static final int BUNDLE_VERSION = 2;
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
        /** SAF filter for anything we can install a game / DLC / mod from: a plain .zip, or a GOG
         *  DRM-free .sh installer (bare, or bundled inside a .zip). A .sh's reported type varies by
         *  document provider (x-sh / x-shellscript / text-plain / octet-stream), and a type missing
         *  here greys the file out entirely — so the list ends in "*&#47;*" to guarantee the user can
         *  always pick their file. Safe: what a file actually IS gets decided by content sniffing
         *  (GogInstallerExtractor / ModImporter), never by its MIME type. */
        public static final String[] GAME_ARCHIVE = {
            "application/zip", "application/x-zip-compressed", "application/octet-stream",
            "application/x-sh", "text/x-sh", "application/x-shellscript", "*/*",
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
