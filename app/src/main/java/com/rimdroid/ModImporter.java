package com.rimdroid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Smart RimWorld mod importer. Takes any .zip and finds each mod root by its
 * {@code About/About.xml} marker, then extracts that root (stripping any wrapping
 * folders, e.g. a zip that double-wraps as {@code Wrapper/RealMod/About/About.xml})
 * into the instance's {@code Mods} folder. Supports multiple mods in one zip.
 */
public final class ModImporter {

    private static final String MARKER = "about/about.xml"; // matched case-insensitively

    public static final class Result {
        public final List<String> imported = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public boolean ok() { return errors.isEmpty() && !imported.isEmpty(); }
    }

    private ModImporter() {}

    /**
     * @param zipFile      local .zip file
     * @param modsDir      destination Mods folder (created if missing)
     * @param fallbackName name to use if a mod sits at the zip root (no wrapping folder)
     */
    public static Result importZip(File zipFile, File modsDir, String fallbackName) {
        Result r = new Result();
        ZipFile zf = null;
        try {
            zf = new ZipFile(zipFile);

            // 1. Find every mod root: the prefix before an "About/About.xml" entry.
            List<String> roots = new ArrayList<>();
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName().replace('\\', '/');
                String lower = name.toLowerCase();
                if (lower.endsWith(MARKER)) {
                    String prefix = name.substring(0, lower.length() - MARKER.length()); // keeps original case, ends with '/' or ""
                    if (!roots.contains(prefix)) roots.add(prefix);
                }
            }
            if (roots.isEmpty()) {
                r.errors.add("No RimWorld mod found (missing About/About.xml).");
                return r;
            }

            if (!modsDir.exists() && !modsDir.mkdirs()) {
                r.errors.add("Cannot create Mods folder: " + modsDir);
                return r;
            }

            // 2. Extract each mod root into Mods/<folderName>.
            for (String prefix : roots) {
                String destName = sanitize(lastSegment(prefix));
                if (destName.isEmpty()) destName = sanitize(fallbackName);
                if (destName.isEmpty()) destName = "mod";

                File destDir = new File(modsDir, destName);
                deleteRecursive(destDir);
                if (!destDir.mkdirs()) { r.errors.add("Cannot create " + destName); continue; }
                String destCanon = destDir.getCanonicalPath();

                Enumeration<? extends ZipEntry> e2 = zf.entries();
                while (e2.hasMoreElements()) {
                    ZipEntry ze = e2.nextElement();
                    String name = ze.getName().replace('\\', '/');
                    if (!prefix.isEmpty() && !name.startsWith(prefix)) continue;
                    String rel = prefix.isEmpty() ? name : name.substring(prefix.length());
                    if (rel.isEmpty()) continue;

                    File out = new File(destDir, rel);
                    // zip-slip guard
                    String outCanon = out.getCanonicalPath();
                    if (!outCanon.equals(destCanon) && !outCanon.startsWith(destCanon + File.separator)) continue;

                    if (ze.isDirectory()) {
                        out.mkdirs();
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (InputStream in = zf.getInputStream(ze)) {
                            copy(in, out);
                        }
                    }
                }
                r.imported.add(destName);
            }
        } catch (IOException ex) {
            r.errors.add(ex.getMessage() != null ? ex.getMessage() : "I/O error");
        } finally {
            if (zf != null) try { zf.close(); } catch (IOException ignored) {}
        }
        return r;
    }

    private static String lastSegment(String prefix) {
        // prefix ends with '/' (folder) or is ""
        String p = prefix;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' || c == '.'
                    || c == '(' || c == ')') sb.append(c);
        }
        return sb.toString().trim();
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
