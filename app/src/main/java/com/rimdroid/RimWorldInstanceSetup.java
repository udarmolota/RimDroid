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
        fixupSteamApi(instanceDir);
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
                if (isVersion16(dir)) {
                    fixupUnityPlayer(dir);
                    fixupSteamApi(dir);
                }
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

    // --- libsteam_api.so normalization -------------------------------------------------------
    // Some repacked 1.6 tarballs (observed: "RimWorld-1.6.4636.tar" on two independent devices,
    // Mali-G615 AND Adreno 735) ship a libsteam_api.so whose SteamAPI_Init HANGS forever under
    // box64 instead of failing cleanly — the game freezes right after "Command line arguments:",
    // before its version banner, with only the audio thread alive. A Steam-depot-clean build
    // (her S25, 1.6.4871) fails Init gracefully ("[S_API] ... did not locate a running instance
    // of Steam") and plays on. Fix: bundle the known-good Steam-origin lib (from the 1.6.4518
    // depot download, md5 ccdf20f0…; the Steamworks flat API is stable across these builds) and
    // normalize every 1.6 instance's copy to it. Only REPLACES an existing file (an instance
    // without the lib takes RimWorld's clean no-Steam path already); original kept as .rdorig.
    private static final String STEAM_LIB = "libsteam_api.so";
    private static final String GAMEFIX_DIR = "gamefix";

    /** Copy bundled game-fix reference files (assets/gamefix/*) into files/gamefix/ so the
     *  context-free setup/reconcile code can read them. Idempotent (size check). Call once from
     *  Application startup and before install-time configure. */
    public static void ensureGameFixAssets(android.content.Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), GAMEFIX_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            android.content.res.AssetManager am = ctx.getAssets();
            for (String name : am.list(GAMEFIX_DIR)) {
                // Guest (x86_64) libs are stored GZIP-COMPRESSED under a ".pack" extension so Android
                // Studio's 16 KB-page alignment check can't flag them. That check inspects the ELF
                // HEADER of any file in the APK (a plain ".bin" rename did NOT fool it — it reads
                // content), but a gzip stream has no ELF magic, so it's invisible. We deliberately do
                // NOT use ".gz": AGP's asset merger auto-DECOMPRESSES ".gz" assets back into a bare
                // ELF, re-triggering the warning. ".pack" is copied verbatim. These libs are loaded by
                // box64 inside the emulation, never by Android's linker, so the alignment rule doesn't
                // apply to them anyway. ".pack" is always decompressed fresh (cheap; keeps the on-disk
                // copy in sync if the bundled lib is ever updated).
                boolean gz = name.endsWith(".pack");
                String outName = gz ? name.substring(0, name.length() - 5) : name;
                File out = new File(dir, outName);
                try (java.io.InputStream raw = am.open(GAMEFIX_DIR + "/" + name)) {
                    if (!gz && out.isFile() && out.length() == raw.available()) continue;   // already extracted
                    java.io.InputStream in = gz ? new java.util.zip.GZIPInputStream(raw) : raw;
                    try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                    }
                    Log.i(TAG, "gamefix asset extracted: " + outName + " (" + out.length() + " bytes)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureGameFixAssets failed (non-fatal): " + t);
        }
    }

    /** Normalize the instance's libsteam_api.so to the bundled known-good copy (see note above).
     *  Idempotent via md5 compare; keeps a one-time .rdorig backup; never fatal. */
    private static void fixupSteamApi(File instanceDir) {
        File ref = new File(AppStorage.requireSingleton().getHomePath(),
                GAMEFIX_DIR + "/" + STEAM_LIB);
        if (!ref.isFile()) { Log.w(TAG, "steam-lib fixup: no extracted reference — skipped"); return; }
        String refMd5 = md5Of(ref);
        if (refMd5 == null) return;
        File data = new File(instanceDir, "RimWorldLinux_Data");
        File[] candidates = {
            new File(data, "Plugins/" + STEAM_LIB),
            new File(data, "Plugins/x86_64/" + STEAM_LIB),
        };
        for (File lib : candidates) {
            if (!lib.isFile()) continue;
            String cur = md5Of(lib);
            if (cur == null || cur.equals(refMd5)) continue;   // unreadable or already normalized
            try {
                File backup = new File(lib.getPath() + ".rdorig");
                if (!backup.exists())
                    java.nio.file.Files.copy(lib.toPath(), backup.toPath());
                java.nio.file.Files.copy(ref.toPath(), lib.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.i(TAG, "steam-lib normalized: " + lib + " (" + cur + " -> " + refMd5
                        + ", original kept as .rdorig)");
            } catch (IOException e) {
                Log.w(TAG, "steam-lib fixup failed for " + lib + ": " + e);
            }
        }
    }

    private static String md5Of(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
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
