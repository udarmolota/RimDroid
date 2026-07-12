package com.rimdroid.xconnector;

import java.io.File;

public class UnixSocketConfig {
    public static final String SYSVSHM_SERVER_PATH = "/tmp/.sysvshm/SM0";
    public static final String ALSA_SERVER_PATH = "/tmp/.sound/AS0";
    public static final String PULSE_SERVER_PATH = "/tmp/.sound/PS0";
    public static final String XSERVER_PATH = "/tmp/.X11-unix/X0";
    public static final String VIRGL_SERVER_PATH = "/tmp/.virgl/V0";
    public static final String VORTEK_SERVER_PATH = "/tmp/.vortek/V0";
    public final String path;

    private UnixSocketConfig(String path) {
        this.path = path;
    }

    public static UnixSocketConfig create(String rootPath, String relativePath) {
        File socketFile = new File(rootPath, relativePath);

        String dirname = getDirname(relativePath);
        if (dirname.lastIndexOf("/") > 0) {
            File socketDir = new File(rootPath, dirname);
            deleteRecursive(socketDir);
            socketDir.mkdirs();
        }
        else socketFile.delete();

        return new UnixSocketConfig(socketFile.getPath());
    }

    // Inlined from Winlator's FileUtils (only these two helpers were used).
    private static String getDirname(String path) {
        int index = path.lastIndexOf('/');
        return index > 0 ? path.substring(0, index) : "/";
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
