package com.rimdroid;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shared post-install setup for RimWorld instances, regardless of their installation source. */
public final class RimWorldInstanceSetup {
    private static final String TAG = "RimDroid/InstanceSetup";
    private static final String UNITY_PLAYER = "UnityPlayer.so";

    // RimWorld 1.6's UnityPlayer.so black-screens on our in-process X server (Screen stuck 0x0):
    // Unity queries the display COUNT too early — before our X server/SDL has enumerated a display —
    // gets 0 and caches it forever, so the game loads fully (defs, mods, menu) but renders BLACK at
    // an unthrottled frame rate. Every 1.6 download we've seen (public 4518 AND latest/Odyssey 4871
    // ship the IDENTICAL player, md5 45408fc1…) has this. The fix is a 6-byte binary patch to the
    // display-count getter — a tiny `mov eax,[g_displayCount]; ret` at file offset 0xE65720 — forced
    // to `mov eax,1; nop; ret` so it always reports one display. Verified byte-exact: patching the
    // raw player (45408fc19d21120d73e89d7bf81fbadd) yields the known-good player
    // (97e71e98acabba3cae5079bd1f158827) that we hand-swapped on 2026-07-12. Applied at install time
    // (like the save fix), so ANY 1.6 download works — no bundling, no "good player" dependency.
    // See memory unityplayer_4871_swap. If Ludeon ships a different player build (bytes at the offset
    // won't match), we skip and log — never corrupt an unknown binary.
    private static final int PLAYER_PATCH_OFFSET = 0xE65720;
    private static final byte[] PLAYER_PATCH_ORIG =
            {(byte)0x8b, 0x05, 0x7a, (byte)0x87, 0x19, 0x01};   // mov eax,[rip+0x119877a]
    private static final byte[] PLAYER_PATCH_NEW =
            {(byte)0xb8, 0x01, 0x00, 0x00, 0x00, (byte)0x90};   // mov eax,1 ; nop  (ret at +6 stays)

    public static boolean configureDetected(File instanceDir) throws IOException {
        return configure(instanceDir, isVersion16(instanceDir));
    }

    public static boolean configure(File instanceDir, boolean rimWorld16) throws IOException {
        if (!rimWorld16) return false;
        createMarker(instanceDir, "rd_x11");
        createMarker(instanceDir, "rd_force_gles");
        // textureCompression=True (via this marker) is now RELIABLE: the box64 CompressBC shim
        // forces the shader's _Quality uniform to a compile-time constant so Mesa/ir3 eliminates
        // the heavy endpoint-search branches before compilation (see docs/BRIEF_texture_
        // compression_turnip_hang.md), on top of the loop-cap safety net. Confirmed loading
        // cleanly on both 1.6.4518 and 1.6.4871 on 2026-07-12. Needed for DLC to fit in RAM on
        // 8 GB devices, so it's the default for every install, not just a manual test.
        createMarker(instanceDir, "rd_texcompress");
        fixupUnityPlayer(instanceDir);
        // Force lazy audio decode: RimWorld ships ~2400 AudioClips with m_PreloadAudioData=true, so
        // Unity mass-decodes every FSB5-Vorbis clip up front. Under box64 that can hang "Loading defs"
        // or OOM on tight-memory devices. Flipping the flag makes each clip decode on first play. Sound
        // is unchanged (raw Vorbis decodes clean since the box64 qsort_r fix) — this is a startup-cost
        // fix, not a sound fix. Idempotent, one byte per clip, no file rewrite.
        com.rimdroid.audio.UnityAudioAssets.patchPreloadAudioData(instanceDir);
        return true;
    }

    /**
     * Best-effort pass over every ALREADY-INSTALLED 1.6 instance, so the UnityPlayer.so fixup
     * (see fixupUnityPlayer) also reaches instances that predate this code — not just fresh
     * installs, which are the only place configure() normally runs. Call once, off the UI
     * thread (e.g. from Application.onCreate via a background Thread); safe to call repeatedly.
     */
    public static void reconcileExistingInstances(File instancesDir) {
        File[] dirs = instancesDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            try {
                if (isVersion16(dir)) fixupUnityPlayer(dir);
                // Version-agnostic (helps 1.5 and 1.6 alike): flip m_PreloadAudioData so the game
                // decodes audio lazily instead of front-loading ~2400 clips. Idempotent — a no-op once
                // patched, so it's safe to run on every startup.
                com.rimdroid.audio.UnityAudioAssets.patchPreloadAudioData(dir);
            } catch (Throwable t) {
                Log.w(TAG, "reconcileExistingInstances: skipped " + dir.getName() + ": " + t);
            }
        }
    }

    /**
     * Apply the 6-byte display-count patch to UnityPlayer.so (see the note above) so the game
     * renders instead of black-screening. Idempotent and version-safe: only patches when the exact
     * original bytes are present at the known offset — if already patched (new bytes present) or a
     * different build (neither matches), it does nothing. Never fatal to the install: any failure
     * just leaves the player as-is and logs a warning.
     */
    private static void fixupUnityPlayer(File instanceDir) {
        File player = new File(instanceDir, UNITY_PLAYER);
        if (!player.isFile()) return;
        try {
            if (player.length() <= PLAYER_PATCH_OFFSET + PLAYER_PATCH_ORIG.length) return;
            byte[] cur = readAt(player, PLAYER_PATCH_OFFSET, PLAYER_PATCH_ORIG.length);
            if (java.util.Arrays.equals(cur, PLAYER_PATCH_NEW)) {
                Log.i(TAG, "UnityPlayer.so already display-count-patched — nothing to do.");
                return;
            }
            if (!java.util.Arrays.equals(cur, PLAYER_PATCH_ORIG)) {
                Log.w(TAG, "UnityPlayer.so: bytes at 0x" + Integer.toHexString(PLAYER_PATCH_OFFSET)
                        + " don't match the known player (got " + hex(cur) + ") — unknown build, "
                        + "skipping the display-count patch (may black-screen; needs a new offset).");
                return;
            }
            writeAt(player, PLAYER_PATCH_OFFSET, PLAYER_PATCH_NEW);
            Log.i(TAG, "UnityPlayer.so: applied display-count patch at 0x"
                    + Integer.toHexString(PLAYER_PATCH_OFFSET) + " (force 1 display → no black screen).");
        } catch (IOException e) {
            Log.w(TAG, "UnityPlayer.so patch skipped: " + e);
        }
    }

    private static byte[] readAt(File f, long offset, int len) throws IOException {
        byte[] out = new byte[len];
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            raf.seek(offset);
            raf.readFully(out);
        }
        return out;
    }

    /** In-place overwrite of {@code len} bytes at {@code offset} (rw, no rewrite of the 33MB file). */
    private static void writeAt(File f, long offset, byte[] bytes) throws IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
            raf.seek(offset);
            raf.write(bytes);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) sb.append(String.format("%02x ", x));
        return sb.toString().trim();
    }

    private static boolean isVersion16(File instanceDir) {
        File versionFile = new File(instanceDir, "Version.txt");
        if (!versionFile.isFile()) return false;
        byte[] data = new byte[128];
        try (FileInputStream in = new FileInputStream(versionFile)) {
            int count = in.read(data);
            if (count <= 0) return false;
            String version = new String(data, 0, count, StandardCharsets.UTF_8).trim();
            return version.startsWith("1.6");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void createMarker(File instanceDir, String name) throws IOException {
        File marker = new File(instanceDir, name);
        if (!marker.exists() && !marker.createNewFile())
            throw new IOException("Cannot create " + marker);
    }

    private RimWorldInstanceSetup() {}
}
