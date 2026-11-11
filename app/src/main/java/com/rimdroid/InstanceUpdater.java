package com.rimdroid;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A newer RimWorld 1.7 build into an existing 1.7 instance, keeping a backup to go back to
 * (2026-11). 1.7 updates often, and a new instance meant moving saves, mods and settings by hand.
 *
 * The instance folder holds the game's files next to what is the player's or ours: Mods/, the saves
 * and settings (unity3d/...), our flags (rd_*), logs and hidden folders. Only the game's files are
 * swapped. The new build is extracted beside the instance first; then the old game files are moved
 * (renamed, same storage) into the backup and the new ones moved in, so the instance is without a
 * game only for an instant and the space needed is the new build plus a copy of the saves. The
 * saves are copied into the backup as well: saves made by the new build may not open on the old one.
 * Only one backup is kept, the latest. A DLC folder in Data/ that the new build lacks (added to the
 * instance separately) is carried over.
 *
 * Going back swaps again: the current game files and saves go into the backup, the backup's come
 * back. Nothing is deleted on the way, so "back" can be undone the same way.
 *
 * Only 1.7 to 1.7: a 1.6 instance keeps its own engine and its saves do not go to 1.7 and back.
 */
public final class InstanceUpdater {
    private static final String TAG = "RimDroid/Updater";
    /** RimWorld 1.7's marker, as in InstallerService. */
    public static final String WAYLITH_MARKER = "RimWorld.runtimeconfig.json";
    private static final String USER_DATA = "unity3d";
    private static final String SAVES_REL = "unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Saves";
    private static final String INFO = "backup.properties";
    /** Headroom on top of the computed need: the file system and the game's first start write too. */
    private static final long MARGIN = 100L * 1024 * 1024;

    private InstanceUpdater() {}

    /** What a backup holds, for the screen. */
    public static final class Backup {
        public final String version;
        public final long created;
        Backup(String version, long created) { this.version = version; this.created = created; }
    }

    // ---- where things are ----

    private static File backupsRoot() {
        return new File(AppStorage.requireSingleton().getInstancesDir().getParentFile(), "instance_backups");
    }

    private static File backupDir(String instance) { return new File(backupsRoot(), instance); }

    private static File stagingDir(String instance) { return new File(backupsRoot(), "." + instance + ".new"); }

    /** A RimWorld 1.7 instance: the only kind that can be updated here. */
    public static boolean canUpdate(File instanceDir) {
        return new File(instanceDir, WAYLITH_MARKER).isFile();
    }

    /** The build in Version.txt ("1.7.4995 rev309"), "" when unreadable. */
    public static String versionOf(File dir) {
        try {
            String text = new String(Files.readAllBytes(new File(dir, "Version.txt").toPath()), StandardCharsets.UTF_8).trim();
            int nl = text.indexOf('\n');
            return (nl < 0 ? text : text.substring(0, nl)).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** The instance's backup, or null when it has none. */
    public static Backup backup(String instance) {
        File info = new File(backupDir(instance), INFO);
        if (!info.isFile()) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(info)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        long created;
        try { created = Long.parseLong(p.getProperty("created", "0")); }
        catch (NumberFormatException e) { created = 0; }
        return new Backup(p.getProperty("version", ""), created);
    }

    /** With the instance itself: its backup goes too. */
    public static void deleteBackup(String instance) {
        deleteTree(backupDir(instance));
        deleteTree(stagingDir(instance));
    }

    /**
     * Not the game's: what the player or the launcher keeps in the instance folder. Mods, saves and
     * settings, our switches (rd_*), logs, and hidden folders ($HOME is the instance on 1.7).
     */
    private static boolean isOurs(String name) {
        return name.equals("Mods") || name.equals(USER_DATA) || name.startsWith(".") || name.startsWith("rd_")
                || name.equals("dotnet.log") || name.startsWith("sigsegv_fault") || name.equals(INFO);
    }

    // ---- update ----

    /**
     * Puts the build in {@code zip} into the instance. Throws with a message for the player when it
     * cannot (not 1.7, not enough space, a broken archive); the instance is as it was then.
     */
    public static void update(String instance, File zip, Consumer<String> progress) throws Exception {
        File dir = AppStorage.requireSingleton().getInstanceDir(instance);
        if (!canUpdate(dir)) throw new Exception("Only a RimWorld 1.7 instance can be updated this way.");
        try (ZipFile zf = new ZipFile(zip)) {
            if (!containsName(zf, WAYLITH_MARKER))
                throw new Exception("This archive is not a RimWorld 1.7 build. Nothing was changed.");
            long need = unpackedSize(zf) + treeSize(new File(dir, SAVES_REL)) + MARGIN;
            long free = backupsRoot().exists() ? backupsRoot().getUsableSpace() : dir.getUsableSpace();
            if (free < need)
                throw new Exception("Not enough free space: about " + mb(need) + " MB is needed, "
                        + mb(free) + " MB is free. Nothing was changed.");
        }

        File staging = stagingDir(instance);
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("cannot create " + staging);
        try {
            progress.accept("Extracting the new build...");
            extract(zip, staging);
            File marker = find(staging, WAYLITH_MARKER);
            if (marker == null) throw new Exception("RimWorld.runtimeconfig.json not found after extraction.");
            File newRoot = marker.getParentFile();
            String newVersion = versionOf(newRoot);
            String oldVersion = versionOf(dir);
            progress.accept("Installed: " + (oldVersion.isEmpty() ? "?" : oldVersion)
                    + ", new: " + (newVersion.isEmpty() ? "?" : newVersion));

            // The old backup goes only now that the new build has been unpacked and checked.
            File backup = backupDir(instance);
            deleteTree(backup);
            File backupGame = new File(backup, "game");
            if (!backupGame.mkdirs()) throw new IOException("cannot create " + backupGame);
            progress.accept("Backing up the saves...");
            File saves = new File(dir, SAVES_REL);
            if (saves.isDirectory()) copyTree(saves, new File(backup, "Saves"));

            progress.accept("Swapping the game files...");
            swapGame(dir, backupGame, newRoot);

            // A DLC folder the new build does not bring (added to the instance on its own) stays.
            File[] oldData = new File(backupGame, "Data").listFiles(File::isDirectory);
            if (oldData != null) {
                for (File d : oldData) {
                    File here = new File(dir, "Data/" + d.getName());
                    if (!here.exists()) {
                        progress.accept("Keeping " + d.getName() + " from the previous build");
                        copyTree(d, here);
                    }
                }
            }
            writeInfo(backup, oldVersion);
            progress.accept("Backup kept: " + (oldVersion.isEmpty() ? "the previous build" : oldVersion));
        } finally {
            deleteTree(staging);
        }
    }

    /**
     * The instance's game files into {@code toBackup}, then {@code newRoot}'s into the instance. If a
     * move fails half way, what was moved goes back where it was.
     */
    private static void swapGame(File dir, File toBackup, File newRoot) throws IOException {
        List<File[]> done = new ArrayList<>();   // {from, to}, to undo
        try {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!isOurs(f.getName())) move(f, new File(toBackup, f.getName()), done);
            File[] fresh = newRoot.listFiles();
            if (fresh != null) for (File f : fresh) {
                if (isOurs(f.getName())) continue;   // the game's own empty Mods/ and the like: ours stay
                move(f, new File(dir, f.getName()), done);
            }
        } catch (IOException e) {
            for (int i = done.size() - 1; i >= 0; i--) {
                File[] m = done.get(i);
                if (!m[1].renameTo(m[0])) Log.e(TAG, "cannot move back " + m[1] + " -> " + m[0]);
            }
            throw e;
        }
    }

    private static void move(File from, File to, List<File[]> done) throws IOException {
        if (to.exists()) throw new IOException("already there: " + to.getName());
        if (!from.renameTo(to)) throw new IOException("cannot move " + from.getName());
        done.add(new File[] { from, to });
    }

    // ---- back to the backup ----

    /**
     * Swaps the instance with its backup: the game files and the saves. The current ones become the
     * backup, so this can be undone the same way.
     */
    public static void restore(String instance, Consumer<String> progress) throws Exception {
        File dir = AppStorage.requireSingleton().getInstanceDir(instance);
        File backup = backupDir(instance);
        Backup info = backup(instance);
        File backupGame = new File(backup, "game");
        if (info == null || !new File(backupGame, WAYLITH_MARKER).isFile())
            throw new Exception("This instance has no backup to go back to.");
        String current = versionOf(dir);

        // Only renames from here on: no extra space is needed.
        File saves = new File(dir, SAVES_REL);
        File backupSaves = new File(backup, "Saves");

        File swap = new File(backup, "game.swap");
        deleteTree(swap);
        if (!swap.mkdirs()) throw new IOException("cannot create " + swap);
        progress.accept("Swapping the game files...");
        swapGame(dir, swap, backupGame);
        deleteTree(backupGame);
        if (!swap.renameTo(backupGame)) throw new IOException("cannot rename " + swap);

        // The saves swap too: the current ones are kept in the backup, never deleted.
        progress.accept("Swapping the saves...");
        File savesSwap = new File(backup, "Saves.swap");
        deleteTree(savesSwap);
        if (saves.isDirectory() && !saves.renameTo(savesSwap)) throw new IOException("cannot move the saves");
        if (backupSaves.isDirectory()) {
            File parent = saves.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!backupSaves.renameTo(saves)) {
                if (savesSwap.isDirectory()) savesSwap.renameTo(saves);
                throw new IOException("cannot move the saves back");
            }
        }
        if (savesSwap.isDirectory() && !savesSwap.renameTo(backupSaves)) throw new IOException("cannot keep the saves");
        writeInfo(backup, current);
        progress.accept("Back on " + (info.version.isEmpty() ? "the previous build" : info.version)
                + "; " + (current.isEmpty() ? "the newer build" : current) + " is the backup now.");
    }

    // ---- helpers ----

    private static void writeInfo(File backup, String version) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", version);
        p.setProperty("created", String.valueOf(System.currentTimeMillis()));
        try (OutputStream out = new FileOutputStream(new File(backup, INFO))) {
            p.store(out, "RimDroid instance backup");
        }
    }

    private static boolean containsName(ZipFile zf, String name) {
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.equals(name) || n.endsWith("/" + name)) return true;
        }
        return false;
    }

    private static long unpackedSize(ZipFile zf) {
        long sum = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            long s = en.nextElement().getSize();
            if (s > 0) sum += s;
        }
        return sum;
    }

    private static void extract(File zip, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        byte[] buf = new byte[65536];
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("Path outside the archive: " + e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = zf.getInputStream(e); OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
                }
            }
        }
    }

    private static File find(File dir, String name) {
        File direct = new File(dir, name);
        if (direct.isFile()) return direct;
        File[] kids = dir.listFiles(File::isDirectory);
        if (kids != null) for (File k : kids) {
            File f = find(k, name);
            if (f != null) return f;
        }
        return null;
    }

    private static long treeSize(File f) {
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += treeSize(k);
        return sum;
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
        } else if (from.isFile()) {
            Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) Log.w(TAG, "cannot delete " + f);
    }

    private static long mb(long bytes) { return bytes / (1024 * 1024); }
}
