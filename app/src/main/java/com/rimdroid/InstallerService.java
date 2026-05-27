package com.rimdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class InstallerService extends Service {

    private static final String TAG      = "RimDroid/Installer";
    private static final int    NOTIF_ID = 1;
    private static final String CHANNEL  = "rimdroid_install";

    // Task identifiers
    public static final String TASK_INSTALL_INSTANCE = "INSTALL_INSTANCE";
    public static final String TASK_INSTALL_DEPS     = "INSTALL_DEPS";

    // Intent extras
    public static final String EXTRA_TASK          = "task";
    public static final String EXTRA_ZIP_PATH      = "zip_path";
    public static final String EXTRA_INSTANCE_NAME = "instance_name";

    // Broadcasts
    public static final String BROADCAST_PROGRESS = "com.rimdroid.INSTALL_PROGRESS";
    public static final String BROADCAST_DONE     = "com.rimdroid.INSTALL_DONE";
    public static final String BROADCAST_ERROR    = "com.rimdroid.INSTALL_ERROR";
    public static final String EXTRA_MESSAGE      = "message";
    public static final String EXTRA_SUCCESS      = "success";

    private AppStorage storage;
    private LauncherPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        storage = AppStorage.requireSingleton();
        prefs   = LauncherPreferences.requireSingleton();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String task = intent.getStringExtra(EXTRA_TASK);
        if (task == null) return START_NOT_STICKY;

        startForeground(NOTIF_ID, buildNotification("Installing..."));

        new Thread(() -> {
            try {
                switch (task) {
                    case TASK_INSTALL_INSTANCE: {
                        String zipPath      = intent.getStringExtra(EXTRA_ZIP_PATH);
                        String instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME);
                        installInstance(zipPath, instanceName);
                        break;
                    }
                    case TASK_INSTALL_DEPS:
                        installDepsFromAssets();
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Install failed", e);
                broadcastError(e.getMessage());
            } finally {
                stopForeground(true);
                stopSelf(startId);
            }
        }).start();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // =========================================================================
    // INSTALL INSTANCE FROM ZIP
    // =========================================================================

    private void installInstance(String zipPath, String instanceName) throws Exception {
        if (zipPath == null)      throw new Exception("No zip path");
        if (instanceName == null || instanceName.isEmpty()) throw new Exception("No instance name");

        File zipFile = new File(zipPath);
        if (!zipFile.exists()) throw new Exception("Zip not found: " + zipPath);

        File instanceDir = storage.getInstanceDir(instanceName);
        if (instanceDir.exists()) {
            broadcastProgress("Removing old instance...");
            deleteDir(instanceDir);
        }
        instanceDir.mkdirs();

        broadcastProgress("Extracting instance...");
        extractZip(zipFile, instanceDir);

        // If zip had a single top-level subfolder, flatten it
        File[] topLevel = instanceDir.listFiles();
        if (topLevel != null && topLevel.length == 1 && topLevel[0].isDirectory()) {
            broadcastProgress("Flattening directory structure...");
            flattenDir(topLevel[0], instanceDir);
        }

        File bin = new File(instanceDir, C.files.RIMWORLD_BIN);
        if (!bin.exists()) {
            bin = findFile(instanceDir, C.files.RIMWORLD_BIN);
            if (bin == null) throw new Exception("RimWorldLinux binary not found inside zip");
            // Already in instanceDir after flatten — if still not at root, something odd
        }
        bin.setExecutable(true);

        prefs.setLastInstanceName(instanceName);
        prefs.setDependenciesInstalled(true);

        broadcastProgress("Instance installed.");
        broadcastDone(true, "Instance '" + instanceName + "' installed successfully");
    }

    // =========================================================================
    // INSTALL DEPS FROM ASSETS (libs.tar.xz)
    // =========================================================================

    private void installDepsFromAssets() throws Exception {
        broadcastProgress("Installing renderer libraries from assets...");

        // For now: extract libs.tar.xz from assets to deps dir
        // The .so files for GL4ES and Zink are bundled in assets/bundles/libs.tar.xz
        File depsDir = new File(storage.getHomePath(), C.deps.ROOT);
        depsDir.mkdirs();

        try (InputStream in = getAssets().open(C.assets.BUNDLES_LIBS)) {
            File tarFile = new File(storage.getCachePath(), "libs.tar.xz");
            copyStream(in, tarFile);
            extractTarXz(tarFile, depsDir);
            tarFile.delete();
        }

        prefs.setDependenciesInstalled(true);
        broadcastDone(true, "Renderer libraries installed");
    }

    private void extractTarXz(File tarXz, File destDir) throws Exception {
        destDir.mkdirs();
        try (InputStream fin = new FileInputStream(tarXz);
             XZCompressorInputStream xzIn = new XZCompressorInputStream(new BufferedInputStream(fin));
             TarArchiveInputStream tarIn = new TarArchiveInputStream(new BufferedInputStream(xzIn, 1024 * 1024))) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath()))
                    throw new Exception("Path traversal in archive: " + entry.getName());
                if (entry.isSymbolicLink()) {
                    Files.createSymbolicLink(out.toPath(),
                            java.nio.file.Paths.get(entry.getLinkName()));
                } else if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = tarIn.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
            }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private void extractZip(File zipFile, File destDir) throws IOException {
        destDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void flattenDir(File src, File dest) {
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) f.renameTo(new File(dest, f.getName()));
        src.delete();
    }

    private File findFile(File dir, String name) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
            if (f.isDirectory()) {
                File found = findFile(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void deleteDir(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }

    private void copyStream(InputStream in, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        byte[] buf = new byte[65536];
        try (FileOutputStream out = new FileOutputStream(dest)) {
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    // =========================================================================
    // BROADCASTS
    // =========================================================================

    private void broadcastProgress(String msg) {
        Log.d(TAG, msg);
        Intent i = new Intent(BROADCAST_PROGRESS);
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    private void broadcastDone(boolean success, String msg) {
        Log.i(TAG, msg);
        Intent i = new Intent(BROADCAST_DONE);
        i.putExtra(EXTRA_SUCCESS, success);
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    private void broadcastError(String msg) {
        Log.e(TAG, "ERROR: " + msg);
        Intent i = new Intent(BROADCAST_ERROR);
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    // =========================================================================
    // NOTIFICATION
    // =========================================================================

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Installation", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("RimDroid")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .build();
    }

    // =========================================================================
    // STATIC STARTERS
    // =========================================================================

    public static void startInstallInstance(Context ctx, String zipPath, String instanceName) {
        Intent i = new Intent(ctx, InstallerService.class);
        i.putExtra(EXTRA_TASK, TASK_INSTALL_INSTANCE);
        i.putExtra(EXTRA_ZIP_PATH, zipPath);
        i.putExtra(EXTRA_INSTANCE_NAME, instanceName);
        ctx.startForegroundService(i);
    }

    public static void startInstallDeps(Context ctx) {
        Intent i = new Intent(ctx, InstallerService.class);
        i.putExtra(EXTRA_TASK, TASK_INSTALL_DEPS);
        ctx.startForegroundService(i);
    }
}
