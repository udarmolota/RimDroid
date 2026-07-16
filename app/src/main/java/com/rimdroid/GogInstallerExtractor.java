package com.rimdroid;

import android.util.Log;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts RimWorld from GOG.com DRM-free Linux installers — the {@code .sh} files distributed
 * directly (and widely re-shared). Each installer is a Makeself 2.2.0 self-extracting archive
 * (shell header + a MojoSetup runtime), with the actual game data appended as a plain ZIP whose
 * entries live under {@code data/noarch/game/}. Because a ZIP is located from its end-of-central-
 * directory record, a standard ZIP reader opens the {@code .sh} directly — the shell + MojoSetup
 * prefix is simply ignored (Apache Commons Compress {@link ZipFile} handles the prefix).
 *
 * <p>Input may be a single {@code .sh}, or a {@code .zip} bundling the base game + DLC installers
 * (the common "full pack" layout). We extract every installer's {@code data/noarch/game/} subtree,
 * stripping that prefix, into one instance dir: the base game contributes {@code RimWorldLinux} +
 * {@code Data/Core}; each DLC merges its {@code Data/<Expansion>} folder — exactly RimWorld's own
 * expansion layout. The result is the same tree {@link InstallerService} already handles, so the
 * normal configure / save-fix tail runs unchanged.
 */
public final class GogInstallerExtractor {
    private static final String TAG = "RimDroid/GOG";

    /** GOG installers put the game under this prefix; detected dynamically but this is the norm. */
    private static final String GAME_MARKER = "/game/";

    private GogInstallerExtractor() {}

    public interface Progress { void report(String message); }

    /**
     * Cheap content sniff: is {@code file} a GOG installer, or a {@code .zip} bundling GOG
     * installers? Returns false for our normal RimWorld zips (which contain RimWorldLinux directly)
     * so the caller keeps using the plain-zip path for those.
     */
    public static boolean looksLikeGogBundle(File file) {
        if (file == null || !file.isFile()) return false;
        // A single makeself/GOG .sh: shell shebang + a Makeself/mojosetup marker in the header.
        if (isMakeselfInstaller(file)) return true;
        // A .zip bundling .sh installers: contains *.sh entries and NOT RimWorldLinux itself.
        if (isZip(file)) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(file)) {
                boolean hasSh = false, hasBin = false;
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    String base = baseName(n);
                    if (base.equals(C.files.RIMWORLD_BIN)) hasBin = true;
                    if (base.toLowerCase().endsWith(".sh")) hasSh = true;
                }
                return hasSh && !hasBin;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Extract every GOG installer in {@code source} into {@code instanceDir}. {@code cacheDir} is
     * used to materialise {@code .sh} files bundled inside an outer zip (a ZIP reader needs random
     * access, so nested installers are spooled to disk, then deleted).
     */
    public static void extract(File source, File instanceDir, File cacheDir, Progress progress)
            throws IOException {
        instanceDir.mkdirs();
        List<File> installers = new ArrayList<>();
        List<File> temps = new ArrayList<>();
        try {
            if (isMakeselfInstaller(source)) {
                installers.add(source);
            } else if (isZip(source)) {
                // Spool each .sh entry to cache so ZipFile can random-access it.
                try (ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(
                        new java.io.FileInputStream(source)))) {
                    ZipEntry e;
                    byte[] buf = new byte[1 << 16];
                    int i = 0;
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory() || !baseName(e.getName()).toLowerCase().endsWith(".sh")) {
                            zis.closeEntry();
                            continue;
                        }
                        File tmp = new File(cacheDir, "gog_" + (i++) + "_" + baseName(e.getName()));
                        try (OutputStream os = new FileOutputStream(tmp)) {
                            int len;
                            while ((len = zis.read(buf)) != -1) os.write(buf, 0, len);
                        }
                        zis.closeEntry();
                        installers.add(tmp);
                        temps.add(tmp);
                    }
                }
            } else {
                throw new IOException("Not a GOG installer or installer bundle: " + source.getName());
            }
            if (installers.isEmpty())
                throw new IOException("No .sh installers found in " + source.getName());

            // Base game (the one carrying RimWorldLinux) first, so its root files land before DLC.
            installers.sort((a, b) -> Boolean.compare(!installerHasBinary(b), !installerHasBinary(a)));

            int n = 0;
            for (File sh : installers) {
                n++;
                progress.report("Extracting installer " + n + "/" + installers.size()
                        + " (" + sh.getName() + ")...");
                extractOneInstaller(sh, instanceDir, progress);
            }
        } finally {
            for (File t : temps) //noinspection ResultOfMethodCallIgnored
                t.delete();
        }
    }

    /** Extract the {@code data/noarch/game/} subtree of one installer into {@code instanceDir}. */
    private static void extractOneInstaller(File installer, File instanceDir, Progress progress)
            throws IOException {
        String canonBase = instanceDir.getCanonicalPath() + File.separator;
        try (ZipFile zf = new ZipFile(installer)) {
            String prefix = detectGamePrefix(zf);
            if (prefix == null)
                throw new IOException(installer.getName() + ": no game payload found "
                        + "(expected entries under data/noarch/game/)");
            Enumeration<ZipArchiveEntry> en = zf.getEntries();
            byte[] buf = new byte[1 << 16];
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                String name = e.getName();
                if (!name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (rel.isEmpty() || shouldSkip(rel)) continue;

                File out = new File(instanceDir, rel);
                if (!(out.getCanonicalPath() + (e.isDirectory() ? File.separator : ""))
                        .startsWith(canonBase))
                    throw new IOException("Path traversal in installer: " + name);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try (InputStream is = zf.getInputStream(e);
                     OutputStream os = new FileOutputStream(out)) {
                    int len;
                    while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
                }
            }
        }
    }

    /** GOG bookkeeping / debug artefacts we don't want in the instance. */
    private static boolean shouldSkip(String rel) {
        String base = baseName(rel).toLowerCase();
        if (base.startsWith("goggame-")) return true;          // GOG catalog id / hashdb / info
        if (base.endsWith("_s.debug")) return true;            // Unity debug symbols (useless here)
        if (base.startsWith("start_rimworld")) return true;    // GOG's own launcher scripts
        if (base.startsWith("how to install")) return true;    // e.g. "How to install Royalty.txt"
        return false;
    }

    /** Find the "data/noarch/game/" prefix (up to and including the ".../game/" segment). */
    private static String detectGamePrefix(ZipFile zf) {
        Enumeration<ZipArchiveEntry> en = zf.getEntries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            int idx = n.indexOf(GAME_MARKER);
            if (idx >= 0) return n.substring(0, idx + GAME_MARKER.length());
        }
        return null;
    }

    /** True if this installer's payload contains the RimWorldLinux binary (i.e. it's the base game). */
    private static boolean installerHasBinary(File installer) {
        try (ZipFile zf = new ZipFile(installer)) {
            Enumeration<ZipArchiveEntry> en = zf.getEntries();
            while (en.hasMoreElements()) {
                if (baseName(en.nextElement().getName()).equals(C.files.RIMWORLD_BIN)) return true;
            }
        } catch (IOException e) {
            Log.w(TAG, "installerHasBinary(" + installer.getName() + "): " + e);
        }
        return false;
    }

    private static boolean isMakeselfInstaller(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int n = (int) Math.min(4096, raf.length());
            byte[] head = new byte[n];
            raf.readFully(head);
            String s = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1);
            return s.startsWith("#!/bin/sh")
                    && (s.contains("Makeself") || s.contains("mojosetup") || s.contains("makeself"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isZip(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 4) return false;
            byte[] m = new byte[4];
            raf.readFully(m);
            return m[0] == 'P' && m[1] == 'K' && m[2] == 3 && m[3] == 4;   // local file header
        } catch (IOException e) {
            return false;
        }
    }

    private static String baseName(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}
