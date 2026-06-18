package com.rimdroid.audio;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * On-device sound-pack generator. Decodes the user's OWN RimWorld audio (FSB5-Vorbis embedded in
 * resources.resource) to clean PCM using the bundled native ARM64 libfmod.so — bypassing box64's
 * broken emulated Vorbis decode — and writes a RimWorld mod (WAVs + clipPath/clipFolderPath patches)
 * into the instance. All local, no redistribution. Entry point: {@link #generatePack}.
 *
 * Runs in the ":fmoddec" process (see FmodDecodeService) so dlopen is the real bionic one and
 * libfmod loads in the normal app namespace (not box64's).
 */
public final class FmodDecodeSpike {
    private static final String TAG = "RimDroid/FmodSpike";

    // libfmoddecode.so is standalone (no linkernsbypass) and is loaded in the ":fmoddec" process,
    // where dlopen is the real bionic one so it can load the bundled libfmod.so cleanly.
    static { try { System.loadLibrary("fmoddecode"); } catch (Throwable ignored) {} }

    /**
     * Decode ONE clip from a slice of resources.resource -> downmix mono + resample to targetRate ->
     * 16-bit mono WAV. Reuses one FMOD system across calls. Returns 0 on success, negative on error.
     */
    public static native int nativeDecodeClip(String fmodLibPath, int fmodVersion, String resourcePath,
                                              long offset, long size, String outWavPath,
                                              int srcRate, int targetRate, int outChannels);

    /** Mod identity (also recognised by GameLauncher.isSoundModInstalled / the audio shim gate). */
    public static final String PACK_ID = "rimdroid.sound";
    public static final String PACK_DIR = "RimDroidSoundGenerated";
    private static final int SFX_RATE = 16000;  // SFX + ambience (mono); menu music is decoded stereo at native rate

    /** Finds an extracted libfmod.so anywhere under the app's deps dir; returns {path, versionHex} or null. */
    private static String[] findFmod(Context ctx) {
        File deps = new File(ctx.getFilesDir(), "dependencies");
        File hit = findFile(deps, "libfmod.so", 6);
        if (hit == null) return null;
        return new String[]{hit.getAbsolutePath(), versionFromDir(hit.getParentFile())};
    }

    /** Parse FMOD_VERSION (0x00MMmmpp) from a "fmod-2.02.24"-style parent dir name; default 2.02.24. */
    private static String versionFromDir(File dir) {
        String name = dir != null ? dir.getName() : "";
        int dash = name.indexOf('-');
        if (dash >= 0) {
            String[] p = name.substring(dash + 1).split("\\.");
            if (p.length == 3) {
                try {
                    return String.format("0x00%02d%02d%02d",
                        Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                } catch (NumberFormatException ignored) {}
            }
        }
        return "0x00020224";
    }

    private static File findFile(File dir, String name, int depth) {
        if (dir == null || depth < 0 || !dir.isDirectory()) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isFile() && k.getName().equals(name)) return k;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                File r = findFile(k, name, depth - 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * Generate the on-device sound pack for an instance: decode every SoundDef clip from the user's
     * own game files, write a RimWorld mod (WAVs + clipPath patch + About.xml) into the instance, and
     * enable it. All local, no redistribution. Returns a summary; logs progress to RimDroid/SoundPack.
     */
    public static String generatePack(Context ctx, File instanceDir) {
        StringBuilder sb = new StringBuilder();
        String[] fmod = findFmod(ctx);
        if (fmod == null) return "libfmod.so not found in deps";
        try { System.load(fmod[0]); } catch (Throwable t) { return "System.load(libfmod) failed: " + t; }
        int ver = (int) Long.decode(fmod[1]).longValue();

        UnityAudioAssets.Plan plan = UnityAudioAssets.buildPlan(instanceDir);
        if (plan == null || plan.decode.isEmpty()) return "empty plan (parse failed?)";

        File resource = new File(instanceDir, "RimWorldLinux_Data/resources.resource");
        if (!resource.isFile()) return "missing resources.resource";
        File mod = new File(instanceDir, "Mods/" + PACK_DIR);
        File sounds = new File(mod, "Sounds");
        File patches = new File(mod, "Patches");
        File about = new File(mod, "About");
        sounds.mkdirs(); patches.mkdirs(); about.mkdirs();

        // 1) Decode every clip to a WAV under Sounds/<rdPath>.wav.
        long t0 = SystemClock.elapsedRealtime();
        int ok = 0, fail = 0; long bytes = 0;
        List<UnityAudioAssets.WorkItem> work = plan.decode;
        for (int i = 0; i < work.size(); i++) {
            UnityAudioAssets.WorkItem w = work.get(i);
            File out = new File(sounds, w.rdPath + ".wav");  // rdPath already starts with "rd/"
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            int r;
            try {
                r = nativeDecodeClip(fmod[0], ver, resource.getAbsolutePath(),
                        w.clip.resOffset, w.clip.resSize, out.getAbsolutePath(),
                        w.clip.freq, SFX_RATE, 1);   // mono SFX
            } catch (Throwable t) { r = -999; }
            if (r == 0) { ok++; bytes += out.length(); } else { fail++; }
            if ((i % 200) == 0) Log.i("RimDroid/SoundPack", "decoded " + i + "/" + work.size() + " (ok=" + ok + " fail=" + fail + ")");
        }
        long dt = SystemClock.elapsedRealtime() - t0;

        // 2) Build the MINIMAL patch set: one clipFolderPath replace per folder (~360) + per-clip
        //    clipPath replaces only for single-clip defs. 2203 per-clip patches hung RimWorld's
        //    startup patch phase; folder patches keep it cheap (≈ the old hand-made pack that loaded).
        StringBuilder ops = new StringBuilder();
        for (Map.Entry<String, String> e : plan.folderPatches.entrySet()) {
            ops.append("  <Operation Class=\"PatchOperationReplace\">\n")
               .append("    <xpath>/Defs//clipFolderPath[text()=\"").append(xmlEsc(e.getKey())).append("\"]</xpath>\n")
               .append("    <value><clipFolderPath>").append(xmlEsc(e.getValue())).append("</clipFolderPath></value>\n")
               .append("  </Operation>\n");
        }
        for (Map.Entry<String, String> e : plan.singlePatches.entrySet()) {
            ops.append("  <Operation Class=\"PatchOperationReplace\">\n")
               .append("    <xpath>/Defs//clipPath[text()=\"").append(xmlEsc(e.getKey())).append("\"]</xpath>\n")
               .append("    <value><clipPath>").append(xmlEsc(e.getValue())).append("</clipPath></value>\n")
               .append("  </Operation>\n");
        }

        // 3) Music: every SongDef's Vorbis is garbled under box64, so silence ALL music, then restore
        //    the menu EntrySong as clean stereo PCM (decoded from the user's own files). Order matters:
        //    the silence-all patch must come BEFORE the EntrySong-specific one.
        boolean menuMusicOk = false;
        if (plan.menuMusic != null) {
            UnityAudioAssets.WorkItem m = plan.menuMusic;
            File mout = new File(sounds, m.rdPath + ".wav");
            File mp = mout.getParentFile(); if (mp != null) mp.mkdirs();
            try {
                // stereo (outChannels=2), native rate (targetRate=0) -> clean music quality
                int r = nativeDecodeClip(fmod[0], ver, resource.getAbsolutePath(),
                        m.clip.resOffset, m.clip.resSize, mout.getAbsolutePath(),
                        m.clip.freq, 0, 2);
                menuMusicOk = (r == 0);
                Log.i("RimDroid/SoundPack", "menu music decode r=" + r + " -> " + mout.length() + " bytes");
            } catch (Throwable t) { Log.w("RimDroid/SoundPack", "menu music decode failed", t); }
        }
        // _silence.wav (tiny silent mono clip) for all the muted SongDefs.
        File silence = new File(sounds, "rd/_silence.wav");
        writeSilenceWav(silence, SFX_RATE, 1.5);
        // silence every SongDef
        ops.append("  <Operation Class=\"PatchOperationReplace\">\n")
           .append("    <xpath>/Defs/SongDef/clipPath</xpath>\n")
           .append("    <value><clipPath>rd/_silence</clipPath></value>\n")
           .append("  </Operation>\n");
        // restore the menu song with clean PCM
        if (menuMusicOk) {
            ops.append("  <Operation Class=\"PatchOperationReplace\">\n")
               .append("    <xpath>/Defs/SongDef[defName=\"EntrySong\"]/clipPath</xpath>\n")
               .append("    <value><clipPath>").append(xmlEsc(plan.menuMusic.rdPath)).append("</clipPath></value>\n")
               .append("  </Operation>\n");
        }

        // About.xml + Patches/Audio.xml
        writeFile(new File(about, "About.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ModMetaData>\n" +
                "  <packageId>" + PACK_ID + "</packageId>\n" +
                "  <name>RimDroid Sound (generated)</name>\n" +
                "  <author>RimDroid</author>\n" +
                "  <supportedVersions><li>1.5</li></supportedVersions>\n" +
                "  <description>Game sound decoded on-device from your own RimWorld copy.</description>\n" +
                "</ModMetaData>\n");
        writeFile(new File(patches, "Audio.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Patch>\n" + ops + "</Patch>\n");

        String modsCfg = enableInModsConfig(instanceDir);

        sb.append("pack: ").append(mod.getAbsolutePath()).append('\n')
          .append("decoded ").append(ok).append(" ok, ").append(fail).append(" fail of ").append(work.size())
          .append(" in ").append(dt / 1000).append("s; ~").append(bytes / (1024 * 1024)).append(" MB\n")
          .append("patches: ").append(plan.folderPatches.size()).append(" folder + ")
          .append(plan.singlePatches.size()).append(" single\n")
          .append("music: all SongDefs silenced; menu music ").append(menuMusicOk ? "decoded" : "MISSING").append('\n')
          .append("ModsConfig: ").append(modsCfg).append('\n');
        Log.i("RimDroid/SoundPack", sb.toString());
        return sb.toString();
    }

    /** First installed instance that has the game files (RimWorldLinux_Data/resources.assets), or null. */
    public static File findFirstInstance(Context ctx) {
        File instances = new File(ctx.getFilesDir(), "instances");
        File[] list = instances.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isDirectory() && new File(f, "RimWorldLinux_Data/resources.assets").isFile()) return f;
        }
        return null;
    }

    private static String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void writeFile(File f, String content) {
        try (FileWriter w = new FileWriter(f)) { w.write(content); }
        catch (Throwable t) { Log.e("RimDroid/SoundPack", "write failed: " + f, t); }
    }

    /** Write a silent mono 16-bit PCM WAV (for muted SongDefs). */
    private static void writeSilenceWav(File f, int rate, double seconds) {
        File parent = f.getParentFile(); if (parent != null) parent.mkdirs();
        int frames = (int) (rate * seconds);
        int dataLen = frames * 2;                 // mono 16-bit
        int byteRate = rate * 2;
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            o.writeBytes("RIFF"); writeLE32(o, 36 + dataLen); o.writeBytes("WAVE");
            o.writeBytes("fmt "); writeLE32(o, 16); writeLE16(o, 1); writeLE16(o, 1);
            writeLE32(o, rate); writeLE32(o, byteRate); writeLE16(o, 2); writeLE16(o, 16);
            o.writeBytes("data"); writeLE32(o, dataLen);
            for (int i = 0; i < dataLen; i++) o.writeByte(0);
        } catch (Throwable t) { Log.e("RimDroid/SoundPack", "silence write failed: " + f, t); }
    }
    private static void writeLE16(java.io.DataOutputStream o, int v) throws java.io.IOException {
        o.writeByte(v & 0xff); o.writeByte((v >> 8) & 0xff);
    }
    private static void writeLE32(java.io.DataOutputStream o, int v) throws java.io.IOException {
        o.writeByte(v & 0xff); o.writeByte((v >> 8) & 0xff); o.writeByte((v >> 16) & 0xff); o.writeByte((v >> 24) & 0xff);
    }

    /** Add our packageId to the instance ModsConfig activeMods (used right after generating the pack). */
    private static String enableInModsConfig(File instanceDir) {
        return setSoundModActive(instanceDir, true);
    }

    /** Toggle the generated sound mod in an instance's ModsConfig.xml — add the packageId to
     *  &lt;activeMods&gt; when {@code active}, remove it when not. This is what the Sound toggle drives,
     *  so enabling/disabling sound needs no in-game mod fiddling. Requires the game to have run once
     *  (RimWorld creates ModsConfig.xml on first launch). Returns a short status. */
    public static String setSoundModActive(File instanceDir, boolean active) {
        File cfg = new File(instanceDir, "unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Config/ModsConfig.xml");
        if (!cfg.isFile()) return "ModsConfig.xml not found (run the game once first)";
        try {
            String x = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
            boolean present = x.contains(">" + PACK_ID + "<");
            if (active) {
                if (present) return "already active";
                int idx = x.indexOf("</activeMods>");
                if (idx < 0) return "no <activeMods> block";
                writeFile(cfg, x.substring(0, idx) + "<li>" + PACK_ID + "</li>" + x.substring(idx));
                return "enabled (added " + PACK_ID + ")";
            } else {
                if (!present) return "already inactive";
                // drop "<li>rimdroid.sound</li>" plus any leading whitespace/newline
                String out = x.replaceAll("\\s*<li>" + java.util.regex.Pattern.quote(PACK_ID) + "</li>", "");
                writeFile(cfg, out);
                return "disabled (removed " + PACK_ID + ")";
            }
        } catch (Throwable t) { return "edit failed: " + t; }
    }

    private FmodDecodeSpike() {}
}
