package com.rimdroid;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public class AppStorage {
    private final String HOME_DIR_PATH;
    private final String CACHE_DIR_PATH;
    private final String LIBRARY_DIR_PATH;
    private static AppStorage singleton;

    private AppStorage(Context applicationContext) {
        HOME_DIR_PATH    = applicationContext.getFilesDir().getAbsolutePath();
        CACHE_DIR_PATH   = applicationContext.getCacheDir().getAbsolutePath();
        LIBRARY_DIR_PATH = applicationContext.getApplicationInfo().nativeLibraryDir;
    }

    public static void init(Context applicationContext) {
        singleton = new AppStorage(applicationContext);
    }

    @Nullable
    public static AppStorage getSingleton() {
        return singleton;
    }

    @NonNull
    public static AppStorage requireSingleton() {
        if (singleton == null) throw new RuntimeException("AppStorage is not initialized");
        return singleton;
    }

    /** /data/data/com.rimdroid/files */
    public String getHomePath() { return HOME_DIR_PATH; }

    /** /data/data/com.rimdroid/cache */
    public String getCachePath() { return CACHE_DIR_PATH; }

    /** Native .so libs dir (ARM64, installed by APK) */
    public String getLibraryPath() { return LIBRARY_DIR_PATH; }

    // ---- Convenience helpers ----

    public String getDepsPath() {
        return HOME_DIR_PATH + "/" + C.deps.ROOT;
    }

    public String getLibsLinuxX86Path() {
        return HOME_DIR_PATH + "/" + C.deps.LIBS_LINUX_X86_64;
    }

    public String getGl4esLibsPath() {
        return HOME_DIR_PATH + "/" + C.deps.LIBS_GL4ES;
    }

    public String getZinkLibsPath() {
        return HOME_DIR_PATH + "/" + C.deps.LIBS_ZINK;
    }

    public File getInstancesDir() {
        return new File(HOME_DIR_PATH, "instances");
    }

    public File getInstanceDir(String name) {
        return new File(getInstancesDir(), name);
    }

    public File getRimWorldBin(String instanceName) {
        return new File(getInstanceDir(instanceName), C.files.RIMWORLD_BIN);
    }
}
