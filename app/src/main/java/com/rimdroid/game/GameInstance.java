package com.rimdroid.game;

import com.rimdroid.AppStorage;
import com.rimdroid.C;
import com.rimdroid.LauncherPreferences;

import java.io.File;
import java.util.ArrayList;

public class GameInstance {

    private final String name;

    public GameInstance(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public String getGamePath() {
        return AppStorage.requireSingleton().getInstanceDir(name).getAbsolutePath();
    }

    /** LD_LIBRARY_PATH passed into BOX64_LD_LIBRARY_PATH env var */
    public String getLdLibraryPathForEmulation() {
        AppStorage storage = AppStorage.requireSingleton();
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();

        // Game dir itself — libsteam_api.so, Data/Managed libs etc.
        paths.add(getGamePath());
        paths.add(getGamePath() + "/Data/Plugins/x86_64");
        paths.add(getGamePath() + "/Libs64");

        // x86_64 system libs (SDL2, OpenAL, etc.)
        paths.add(storage.getLibsLinuxX86Path());

        // Renderer-specific ARM64 libs (gl4es or zink .so files)
        switch (prefs.getRenderer()) {
            case GL4ES:
                paths.add(storage.getGl4esLibsPath());
                break;
            case ZINK_ZFA:
            case ZINK_OSMESA:
                paths.add(storage.getZinkLibsPath());
                break;
        }

        return join(paths, ":");
    }

    /** Native ARM64 library path — passed to zomdroid linker as library_dir_path */
    public String getNativeLibraryPath() {
        AppStorage storage = AppStorage.requireSingleton();
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();
        paths.add(storage.getLibraryPath());          // APK native libs (librimdroid.so etc.)
        paths.add("/system/lib64");

        switch (prefs.getRenderer()) {
            case GL4ES:
                paths.add(storage.getGl4esLibsPath());
                break;
            case ZINK_ZFA:
            case ZINK_OSMESA:
                paths.add(storage.getZinkLibsPath());
                break;
        }

        return join(paths, ":");
    }

    /** Args passed to RimWorldLinux binary (none needed by default) */
    public String[] getArgs() {
        ArrayList<String> args = new ArrayList<>();
        // RimWorld accepts no special startup args normally
        // Could add: -logfile /path, -batchmode, etc.
        return args.toArray(new String[0]);
    }

    public boolean isInstalled() {
        return new File(getGamePath(), C.files.RIMWORLD_BIN).exists();
    }

    private static String join(ArrayList<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
