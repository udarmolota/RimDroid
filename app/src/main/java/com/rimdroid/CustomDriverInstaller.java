package com.rimdroid;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a user-supplied Vulkan driver (AdrenoTools-style) and stores it as the single
 * {@code custom_driver.so} in the deps dir, where the renderer can dlopen it by name
 * (selected per-instance via the "Custom driver (imported)" picker entry).
 *
 * <p>Accepts either:
 * <ul>
 *   <li>a raw <b>.so</b> file — copied verbatim, or</li>
 *   <li>an AdrenoTools driver <b>.zip</b> — we read {@code meta.json}'s {@code libraryName}
 *       (falling back to the first {@code .so}) and extract that entry.</li>
 * </ul>
 * The driver is device-global (one file), so it lives outside any instance and survives
 * {@code libs.tar.xz} re-extraction (that overwrites only its own bundled members by name).
 */
public final class CustomDriverInstaller {

    private CustomDriverInstaller() {}

    /** The on-disk path of the imported driver (may not exist yet). */
    public static File driverFile() {
        return new File(AppStorage.requireSingleton().getHomePath(), C.deps.CUSTOM_DRIVER);
    }

    public static boolean isInstalled() {
        File f = driverFile();
        return f.isFile() && f.length() > 0;
    }

    public static boolean remove() {
        File f = driverFile();
        return !f.exists() || f.delete();
    }

    /**
     * Import the driver pointed to by {@code uri}. {@code displayName} is the picked file name
     * (used only to decide .so vs .zip). Runs on the caller's thread (do it off the UI thread).
     *
     * @return the number of bytes written to custom_driver.so
     * @throws IOException on read/extract failure or if no .so could be found in a zip
     */
    public static long importFrom(@NonNull Context ctx, @NonNull Uri uri,
                                  @Nullable String displayName) throws IOException {
        File dest = driverFile();
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create deps dir: " + parent);

        boolean isZip = displayName != null && displayName.toLowerCase().endsWith(".zip");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open the selected file");
            if (isZip) {
                return extractSoFromZip(new BufferedInputStream(in), dest);
            } else {
                return copyTo(in, dest);
            }
        }
    }

    private static long copyTo(InputStream in, File dest) throws IOException {
        long total = 0;
        try (OutputStream os = new FileOutputStream(dest, false)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) { os.write(buf, 0, r); total += r; }
        }
        return total;
    }

    /**
     * Pull the driver .so out of an AdrenoTools zip. We can't seek the stream, so we buffer
     * each .so entry and remember meta.json's libraryName; afterwards we write the matching
     * (or first) .so. Driver zips are small (~10-20 MB), so buffering is acceptable.
     */
    private static long extractSoFromZip(InputStream in, File dest) throws IOException {
        String libraryName = null;
        byte[] firstSo = null;
        byte[] namedSo = null;
        String firstSoName = null;

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) { zis.closeEntry(); continue; }
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.equalsIgnoreCase("meta.json")) {
                    libraryName = parseLibraryName(readAll(zis, buf));
                } else if (base.toLowerCase().endsWith(".so")) {
                    byte[] data = readAll(zis, buf);
                    if (firstSo == null) { firstSo = data; firstSoName = base; }
                    if (libraryName != null && base.equals(libraryName)) namedSo = data;
                }
                zis.closeEntry();
                // If we already matched the named lib, we still must keep scanning only if
                // meta.json came AFTER the .so; to be safe we finish the whole archive.
            }
        }

        byte[] chosen = namedSo != null ? namedSo : firstSo;
        if (chosen == null) throw new IOException("No .so found inside the driver zip");
        // meta.json may have named a lib we buffered as firstSo (when meta came after it):
        if (namedSo == null && libraryName != null && firstSoName != null
                && !firstSoName.equals(libraryName)) {
            // libraryName referenced a different file we didn't capture; firstSo is our best bet.
            chosen = firstSo;
        }
        try (OutputStream os = new FileOutputStream(dest, false)) {
            os.write(chosen);
        }
        return chosen.length;
    }

    private static byte[] readAll(InputStream in, byte[] buf) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    @Nullable
    private static String parseLibraryName(byte[] json) {
        try {
            JSONObject o = new JSONObject(new String(json, "UTF-8"));
            String lib = o.optString("libraryName", null);
            return (lib != null && !lib.isEmpty()) ? lib : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
