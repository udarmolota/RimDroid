package com.rimdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Screen-independent completion feedback (works regardless of which screen is open). */
    private void toast(String msg) {
        ui.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

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

        // Make sure the shared deps bundle is present AND at the current revision before setting up the
        // instance (which stamps deps-installed at the end). This guarantees e.g. the x86_64 X11 client
        // libs are laid down for a fresh install even if the user jumps straight to "Add Instance", and
        // re-extracts for updaters whose on-disk deps predate a bundle bump. Idempotent + quick (~24 MB).
        if (!prefs.areDependenciesInstalled()) ensureDepsExtracted();

        File instanceDir = storage.getInstanceDir(instanceName);
        // NEVER overwrite an existing instance — that silently wiped the user's data when a new
        // instance was given a name that already existed (e.g. auto-filled from the zip filename).
        // Require a unique name; the user must delete the old instance first if they really mean to.
        File[] existing = instanceDir.exists() ? instanceDir.listFiles() : null;
        if (existing != null && existing.length > 0) {
            throw new Exception("Instance '" + instanceName + "' already exists — pick another name "
                    + "(or delete it first).");
        }

        // Two input shapes:
        //  (1) GOG DRM-free installer(s): a single .sh, or a .zip bundling the base game + DLC .sh
        //      installers. Extracted by GogInstallerExtractor (strips the data/noarch/game/ prefix,
        //      merges DLC into Data/), yielding RimWorldLinux at the instance root directly.
        //  (2) our normal RimWorld .zip: RimWorldLinux somewhere inside → extract + re-root.
        boolean gog = GogInstallerExtractor.looksLikeGogBundle(zipFile);
        if (gog) {
            broadcastProgress("Extracting GOG installer(s)...");
            GogInstallerExtractor.extract(zipFile, instanceDir,
                    new File(storage.getCachePath()), this::broadcastProgress);
        } else {
            // Validate the archive BEFORE creating any folders (Zomdroid-style): it must contain the
            // RimWorldLinux binary somewhere. Otherwise we'd extract a non-RimWorld archive and leave
            // an orphaned instance folder that the launcher silently hides (fails isInstalled()).
            broadcastProgress("Checking archive...");
            if (!zipContainsEntry(zipFile, C.files.RIMWORLD_BIN)) {
                throw new Exception("RimWorldLinux not found in the archive — this doesn't look like a "
                        + "RimWorld Linux build (nor a GOG installer). Nothing was installed.");
            }
            instanceDir.mkdirs();
            broadcastProgress("Extracting instance...");
            extractZip(zipFile, instanceDir);
        }

        // Re-root: the game files may sit inside a wrapper folder (e.g. "game/RimWorldLinux") at any
        // depth. Find RimWorldLinux and lift its folder's contents to the instance top, dropping the
        // wrappers, so isInstalled() (which checks the root) passes. If it's somehow missing after
        // extraction, delete the whole instance dir — never leave an orphaned, hidden instance.
        // (GOG extraction already lands RimWorldLinux at the root, so this is a no-op there.)
        File bin = new File(instanceDir, C.files.RIMWORLD_BIN);
        if (!bin.exists()) {
            File found = findFile(instanceDir, C.files.RIMWORLD_BIN);
            if (found == null) {
                deleteDir(instanceDir);
                throw new Exception("RimWorldLinux not found after extraction — nothing was installed.");
            }
            File gameRoot = found.getParentFile();
            if (gameRoot != null && !gameRoot.equals(instanceDir)) {
                broadcastProgress("Flattening directory structure...");
                reRoot(gameRoot, instanceDir);
            }
            bin = new File(instanceDir, C.files.RIMWORLD_BIN);
        }
        bin.setExecutable(true);

        // Game-fix reference assets must exist before configure (steam-lib normalization reads
        // them from files/gamefix). Idempotent; normally a no-op after first app start.
        RimWorldInstanceSetup.ensureGameFixAssets(getApplicationContext());
        if (RimWorldInstanceSetup.configureDetected(instanceDir))
            broadcastProgress("Configured RimWorld 1.6 renderer and texture compression.");

        // Install-time save fix (Assembly-CSharp bspatch) DISABLED 2026-07-14: the save-bug root
        // (box64's broken Android qsort mis-building Mono IMT tables) is fixed at the root in
        // box64/wrappedlibc.c, so the IL bypass is redundant. Code + assets kept for rollback.
        // broadcastProgress("Applying save fix...");
        // SaveFixInstaller.applyTo(getApplicationContext(), instanceDir);

        prefs.setLastInstanceName(instanceName);
        prefs.setDependenciesInstalled(true);

        broadcastProgress("Instance installed.");
        broadcastDone(true, "Instance '" + instanceName + "' installed — ready to launch");
    }

    // =========================================================================
    // INSTALL DEPS FROM ASSETS (libs.tar.xz)
    // =========================================================================

    /** TASK_INSTALL_DEPS entry point: extract the bundle, then signal completion to the UI. */
    private void installDepsFromAssets() throws Exception {
        ensureDepsExtracted();
        broadcastDone(true, "Renderer libraries installed");
    }

    /**
     * Extract assets/bundles/libs.tar.xz into the deps dir (renderer libs, x86_64 game + X11 client libs,
     * Vulkan drivers, fmod, …) and stamp the current bundle revision. Overwrites in place, so it also
     * refreshes an older on-disk bundle after a BUNDLE_VERSION bump. No broadcastDone — safe to call as a
     * sub-step of another install task.
     */
    private void ensureDepsExtracted() throws Exception {
        broadcastProgress("Installing renderer libraries from assets...");
        File depsDir = new File(storage.getHomePath(), C.deps.ROOT);
        depsDir.mkdirs();
        try (InputStream in = getAssets().open(C.assets.BUNDLES_LIBS)) {
            File tarFile = new File(storage.getCachePath(), "libs.tar.xz");
            copyStream(in, tarFile);
            extractTarXz(tarFile, depsDir);
            tarFile.delete();
        }
        prefs.setDependenciesInstalled(true);
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
                        while ((len = tarIn.read(buf)) != -1) fos.write(buf, 0, len);
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
                        while ((len = zis.read(buf)) != -1) fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** True if the archive contains an entry whose file name (last path segment) equals {@code fileName}.
     *  Uses ZipFile (reads the central directory directly) so it's instant even for a ~200MB game zip. */
    private boolean zipContainsEntry(File zipFile, String fileName) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                int slash = n.lastIndexOf('/');
                String base = (slash >= 0) ? n.substring(slash + 1) : n;
                if (base.equals(fileName)) return true;
            }
        }
        return false;
    }

    /**
     * Move every child of {@code gameRoot} up to {@code instanceDir}, then remove the now-empty wrapper
     * folders between them. {@code gameRoot} must be a descendant of {@code instanceDir}. This lifts a
     * nested game ("instance/game/RimWorldLinux*") to the instance root so isInstalled() passes.
     */
    private void reRoot(File gameRoot, File instanceDir) throws IOException {
        File[] kids = gameRoot.listFiles();
        if (kids != null) {
            for (File k : kids) {
                File target = new File(instanceDir, k.getName());
                if (target.exists())
                    throw new IOException("Name clash while flattening: " + k.getName());
                if (!k.renameTo(target))
                    throw new IOException("Could not move " + k.getName() + " to the instance root");
            }
        }
        // Delete the wrapper chain from gameRoot up to (but not including) instanceDir.
        File p = gameRoot;
        while (p != null && !p.equals(instanceDir)) {
            File parent = p.getParentFile();
            deleteDir(p);
            p = parent;
        }
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
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
        }
    }

    // =========================================================================
    // BROADCASTS
    // =========================================================================

    private void broadcastProgress(String msg) {
        Log.d(TAG, msg);
        Intent i = new Intent(BROADCAST_PROGRESS);
        i.setPackage(getPackageName());   // explicit target so RECEIVER_NOT_EXPORTED receivers get it
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    private void broadcastDone(boolean success, String msg) {
        Log.i(TAG, msg);
        toast("✓ " + msg);   // ✓ — always visible, even on other screens
        Intent i = new Intent(BROADCAST_DONE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_SUCCESS, success);
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    private void broadcastError(String msg) {
        Log.e(TAG, "ERROR: " + msg);
        toast("Install failed: " + (msg != null ? msg : "unknown error"));
        Intent i = new Intent(BROADCAST_ERROR);
        i.setPackage(getPackageName());
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
