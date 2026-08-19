package com.rimdroid;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runs RimWorld 1.6 through a relocatable stand-in for its launcher binary, on the devices that need it.
 *
 * <p>RimWorld's {@code RimWorldLinux} is a non-PIE executable: it only runs from one fixed low address.
 * On some phones that address belongs to someone else — on a Huawei Kirin 8000 the Android runtime's
 * Java heap spans 0x10000-0x32010000 from the moment the process is created — and box64 then cannot
 * load the game at all. A non-PIE image cannot be moved, so the launcher itself has to go.
 *
 * <p>On 1.6 that launcher is 14 KB that loads UnityPlayer.so and calls PlayerMain; our stand-in
 * ({@code shim/rd_unity_shim.c}, built as PIE) does the same and loads at any address. It is installed
 * under the original name, with the original kept as {@code RimWorldLinux.rdorig}, so argv[0] and
 * /proc/self/exe still resolve RimWorldLinux_Data the way Unity expects.
 *
 * <p>This only happens where it is needed: the native constructor in rimdroid.c reserves the low range
 * at library-load time and publishes {@code RIMDROID_ELF_RESERVE} when it succeeds. If that variable is
 * present the game's own binary can be placed as usual and we restore it; the swap is therefore
 * self-correcting and leaves every working device on exactly the path it uses today.
 *
 * <p>1.5 is out of scope: there the launcher is ~2 MB with SDL2 statically linked in, and UnityPlayer.so
 * resolves SDL symbols from it, so a bare stand-in would break windowing and input.
 */
public final class UnityShimInstaller {

    private static final String TAG    = "RimDroid/UnityShim";
    private static final String BIN    = C.files.RIMWORLD_BIN;      // "RimWorldLinux"
    private static final String ORIG   = BIN + ".rdorig";
    private static final String MARKER = ".rd_unity_shim";
    private static final String ASSET  = "shim/rd_unity_shim";

    private UnityShimInstaller() {}

    /** Installs or removes the stand-in as this device requires. Best-effort: never throws. */
    public static void applyTo(Context ctx, File instanceDir) {
        try {
            File bin    = new File(instanceDir, BIN);
            File orig   = new File(instanceDir, ORIG);
            File marker = new File(instanceDir, MARKER);
            boolean installed = marker.isFile() && orig.isFile();

            // The low range was ours to reserve → the game's own launcher will load fine.
            boolean lowRangeAvailable = Os.getenv("RIMDROID_ELF_RESERVE") != null;

            if (lowRangeAvailable || !isVersion16(instanceDir)) {
                if (installed) restore(bin, orig, marker);
                return;
            }
            if (installed) {
                Log.i(TAG, "stand-in already installed in " + instanceDir.getName());
                return;
            }
            if (!bin.isFile()) return;   // nothing installed here yet

            File tmp = new File(instanceDir, BIN + ".rdtmp");
            try (InputStream in = ctx.getAssets().open(ASSET)) {
                copy(in, tmp);
            } catch (IOException e) {
                Log.w(TAG, "no bundled stand-in (" + ASSET + "): " + e);
                tmp.delete();
                return;
            }
            if (!orig.exists() && !bin.renameTo(orig)) {
                Log.e(TAG, "cannot move the original launcher aside");
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(bin)) {
                Log.e(TAG, "cannot put the stand-in in place; restoring the original");
                orig.renameTo(bin);
                tmp.delete();
                return;
            }
            bin.setExecutable(true, false);
            marker.createNewFile();
            GameLauncher.postLog("This device cannot give the game its fixed load address, "
                    + "so RimWorld 1.6 is started through RimDroid's relocatable launcher.");
            Log.i(TAG, "stand-in installed in " + instanceDir.getName());
        } catch (Throwable t) {
            Log.w(TAG, "stand-in setup failed", t);
        }
    }

    private static void restore(File bin, File orig, File marker) {
        if (bin.delete() && orig.renameTo(bin)) {
            bin.setExecutable(true, false);
            marker.delete();
            Log.i(TAG, "restored the game's own launcher — its load address is available here");
        } else {
            Log.w(TAG, "could not restore the game's own launcher");
        }
    }

    private static void copy(InputStream in, File dst) throws IOException {
        try (OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static boolean isVersion16(File instanceDir) {
        File versionFile = new File(instanceDir, "Version.txt");
        if (!versionFile.isFile()) return false;
        byte[] data = new byte[128];
        try (FileInputStream in = new FileInputStream(versionFile)) {
            int count = in.read(data);
            if (count <= 0) return false;
            return new String(data, 0, count, StandardCharsets.UTF_8).trim().startsWith("1.6");
        } catch (IOException ignored) {
            return false;
        }
    }
}
