package com.rimdroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup / restore of a RimWorld instance's user data — game saves ({@code Saves/}) and
 * settings ({@code Config/}: Prefs.xml, ModsConfig.xml, KeyPrefs.xml, mod settings).
 * Both live under the instance's persistentDataPath
 * ({@code <instance>/unity3d/Ludeon Studios/RimWorld by Ludeon Studios/}).
 *
 * Export zips those folders; import extracts them back (restricted to Saves/ + Config/,
 * zip-slip guarded). Mirrors the mod importer's zip handling.
 */
public final class GameDataTransfer {

    /** Top-level folders included in a backup. */
    private static final String[] PARTS = { "Saves", "Config" };

    public static final class Result {
        public final List<String> items = new ArrayList<>(); // which parts were handled
        public String error;
        public long bytes;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    private GameDataTransfer() {}

    /** Zip the Saves/ and Config/ folders of {@code userDir} into {@code rawOut}. */
    public static Result export(File userDir, OutputStream rawOut) {
        Result r = new Result();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[1 << 16];
            for (String part : PARTS) {
                File dir = new File(userDir, part);
                if (!dir.isDirectory()) continue;
                zipDir(dir, part, zos, buf, r);
                r.items.add(part);
            }
            if (r.items.isEmpty()) r.error = "Nothing to export (no Saves/Config yet).";
        } catch (IOException e) {
            r.error = msg(e);
        }
        return r;
    }

    private static void zipDir(File dir, String prefix, ZipOutputStream zos, byte[] buf, Result r)
            throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        if (kids.length == 0) {                       // preserve empty dirs
            zos.putNextEntry(new ZipEntry(prefix + "/"));
            zos.closeEntry();
            return;
        }
        for (File f : kids) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) {
                zipDir(f, name, zos, buf, r);
            } else {
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) { zos.write(buf, 0, n); r.bytes += n; }
                }
                zos.closeEntry();
            }
        }
    }

    /** Restore a backup zip into {@code userDir} (only Saves/ and Config/ entries). */
    public static Result importZip(File zipFile, File userDir) {
        Result r = new Result();
        if (!userDir.exists() && !userDir.mkdirs()) { r.error = "Cannot create user data dir"; return r; }
        final String destCanon;
        try { destCanon = userDir.getCanonicalPath(); } catch (IOException e) { r.error = msg(e); return r; }

        Set<String> parts = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                String top = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
                if (!isAllowed(top)) { zis.closeEntry(); continue; }   // ignore anything outside Saves/Config

                File out = new File(userDir, name);
                String oc = out.getCanonicalPath();
                if (!oc.equals(destCanon) && !oc.startsWith(destCanon + File.separator)) { // zip-slip guard
                    zis.closeEntry();
                    continue;
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zis.read(buf)) > 0) { os.write(buf, 0, n); r.bytes += n; }
                    }
                }
                parts.add(top);
                zis.closeEntry();
            }
            r.items.addAll(parts);
            if (parts.isEmpty()) r.error = "No Saves/Config found in this zip.";
        } catch (IOException ex) {
            r.error = msg(ex);
        }
        return r;
    }

    private static boolean isAllowed(String top) {
        for (String p : PARTS) if (p.equals(top)) return true;
        return false;
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : "I/O error"; }
}
