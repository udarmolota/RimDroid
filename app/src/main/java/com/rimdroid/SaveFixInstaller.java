package com.rimdroid;

import android.content.Context;
import android.util.Log;

import io.sigpipe.jbsdiff.Patch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Install-time SAVE FIX.
 *
 * box64 mis-builds Mono's IMT conflict-thunk for {@code Verse.IExposable::ExposeData} → serialization
 * dispatches to the wrong method → save corruption (any depth). Our patcher rewrites the 4 Scribe
 * callsites ({@code callvirt IExposable::ExposeData} → {@code call} our helper) so serialization runs the
 * concrete ExposeData via the class vtable, bypassing the broken IMT.
 *
 * We precompute that patch PER KNOWN RimWorld build on the PC and ship only the tiny BINARY DIFF
 * (bsdiff) in {@code assets/savefix/<original-sha256>.bsdiff} — NOT the game dll (that stays the user's
 * own downloaded copy; we only transform it). On install we hash the user's Assembly-CSharp.dll, look up
 * the matching diff, bspatch it in place, and drop the helper dll next to it. An unknown build → no-op
 * (the caller can offer the standalone SaveFix mod instead).
 */
public final class SaveFixInstaller {
    private static final String TAG    = "RimDroid/SaveFix";
    private static final String HELPER = "RimDroid.SaveBypass.dll";
    private static final String MARKER = ".rd_savefix";

    /** Apply the bundled save-fix to this instance if its RimWorld build is covered.
     *  @return true = patched (or already patched); false = build not covered / nothing to do. */
    public static boolean applyTo(Context ctx, File instanceDir) {
        File managed = new File(instanceDir, "RimWorldLinux_Data/Managed");
        File dll     = new File(managed, "Assembly-CSharp.dll");
        if (!dll.isFile()) {
            Log.i(TAG, "no Assembly-CSharp.dll in " + instanceDir.getName() + " — skip");
            return false;
        }
        if (new File(managed, MARKER).isFile()) {
            Log.i(TAG, "save-fix already applied (marker present) — skip");
            return true;
        }

        try {
            String hash = sha256(dll);

            // Look up the diff for this exact build. Missing asset = build not covered.
            byte[] diff;
            try (InputStream in = ctx.getAssets().open("savefix/" + hash + ".bsdiff")) {
                diff = readAll(in);
            } catch (IOException notCovered) {
                Log.w(TAG, "build " + hash.substring(0, 12) + "… NOT covered by a bundled patch — "
                        + "save-fix not applied (offer the SaveFix mod)");
                return false;
            }

            byte[] orig = Files.readAllBytes(dll.toPath());

            // 1) helper dll FIRST, so the patched assembly's reference resolves at runtime.
            try (InputStream hin = ctx.getAssets().open("savefix/" + HELPER)) {
                copy(hin, new File(managed, HELPER));
            }
            // 2) pristine backup (revertable), then bspatch original -> temp.
            File backup = new File(managed, "Assembly-CSharp.dll.rdorig");
            if (!backup.exists()) Files.copy(dll.toPath(), backup.toPath());
            File tmp = new File(managed, "Assembly-CSharp.dll.rdtmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                Patch.patch(orig, diff, out);   // jbsdiff: reconstruct the patched dll
            }
            if (tmp.length() == 0) { tmp.delete(); throw new IllegalStateException("bspatch produced 0 bytes"); }

            // 3) swap in the patched dll LAST (atomic on the same filesystem).
            Files.move(tmp.toPath(), dll.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // 4) marker so we never re-patch an already-patched instance.
            try (FileOutputStream m = new FileOutputStream(new File(managed, MARKER))) {
                m.write((hash + "\n").getBytes());
            }
            Log.i(TAG, "save-fix applied for build " + hash.substring(0, 12) + "… (" + dll.length() + " bytes)");
            return true;
        } catch (Throwable t) {
            // The game dll is only replaced at the very end, so on any earlier failure it stays intact.
            Log.e(TAG, "save-fix failed — game dll left untouched", t);
            return false;
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(f)) {
            int n; while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void copy(InputStream in, File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private SaveFixInstaller() {}
}
