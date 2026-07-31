package com.rimdroid;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal directory→zip packer. Used to turn a freshly-downloaded DLC/mod folder into a single
 * portable archive in the public /Download/RimDroid folder, which the existing {@link ModImporter}
 * then unwraps into an instance (it finds the About/About.xml root and strips wrappers like Data/).
 */
public final class ZipUtil {

    private ZipUtil() {}

    /** Zip the entire contents of {@code srcDir} into {@code destZip} (entries relative to srcDir). */
    public static void zipDir(File srcDir, File destZip) throws IOException {
        File parent = destZip.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destZip)))) {
            addEntries(srcDir, srcDir, zos);
        }
    }

    private static void addEntries(File root, File current, ZipOutputStream zos) throws IOException {
        File[] kids = current.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            // Skip DepotDownloader's bookkeeping dir — not part of the content.
            if (f.isDirectory() && f.getName().equals(".DepotDownloader")) continue;
            String rel = root.toURI().relativize(f.toURI()).getPath();  // forward-slash relative path
            if (f.isDirectory()) {
                if (!rel.endsWith("/")) rel += "/";
                zos.putNextEntry(new ZipEntry(rel));
                zos.closeEntry();
                addEntries(root, f, zos);
            } else {
                zos.putNextEntry(new ZipEntry(rel));
                try (InputStream in = new FileInputStream(f)) {
                    copy(in, zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }
}
