package com.rimdroid.game;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RimWorld 1.7 (experimental, build flag dotnetEngine=true). 1.7 no longer runs on Unity: it is
 * Ludeon's Waylith engine on .NET 10, Vulkan and GLFW. Its managed code is architecture-free, so the
 * plan is the official .NET 10 runtime for Android (CoreCLR, android-arm64 runtime pack, fetched at
 * build time) plus Android versions of the few native libraries the engine calls.
 *
 * Stage 1 (this class + ClrActivity): start the runtime on the game's RimWorld.dll in its own process
 * and log how far it gets. The game's own x86-64 .so files cannot load on ARM; the first one it asks
 * for is the next thing to replace. The framework the game shipped (their modified 10.0.10) is left
 * out in favour of the stock one from the APK.
 */
public final class DotnetEngine {
    private static final String TAG = "RimDroid/dotnet";
    /** Where the build put the framework assemblies inside the APK (fetchDotnetRuntime). */
    static final String ASSETS = "dotnet/framework";

    private DotnetEngine() {}

    /** Intent extra: the GPU's own BC support as the launcher asked it (TextureCache.gpuBc). */
    public static final String EXTRA_GPU_BC = "gpu_bc";
    private static int gpuBc = -1;

    /** ClrActivity hands over the launcher's answer: the game process does not ask Vulkan itself
     *  before the game, as libvulkan.so there is the wrapper that reports BC when converting. */
    public static void setGpuBc(int value) { gpuBc = value; }

    /**
     * The textures go through BC -> ETC2/EAC re-encoding (libvulkan.so, "BC on GPUs without it"): on
     * GPUs without BC textures, or for a test on any phone with the file rd_no_bc in the game folder.
     * The engine's GPU texture encoder is turned off with it. Decided by the driver's answer: an
     * Adreno 650 on a 2021 driver has no BC either, and with only Mali/Immortalis/PowerVR named here
     * its encoder stayed on and items drew empty (2026-10-21); the names are the fallback.
     */
    public static boolean bcConversion(GameInstance gi) {
        if (new File(gi.getGamePath(), "rd_no_bc").isFile()) return true;
        if (gpuBc == 0) return true;
        if (gpuBc == 1) return false;
        String renderer = com.rimdroid.GpuInfo.query().renderer;
        if (renderer == null) return false;
        String r = renderer.toLowerCase(Locale.ROOT);
        return r.contains("mali") || r.contains("immortalis") || r.contains("powervr");
    }

    /** The instance holds RimWorld 1.7 (Waylith): RimWorld.dll + its runtimeconfig, no Unity data. */
    public static boolean isWaylith(GameInstance gi) {
        File root = new File(gi.getGamePath());
        return new File(root, "RimWorld.dll").isFile() && new File(root, "RimWorld.runtimeconfig.json").isFile();
    }

    /** This APK carries the .NET runtime (built with dotnetEngine=true). */
    public static boolean isAvailable(Context context) {
        if (!new File(context.getApplicationInfo().nativeLibraryDir, "libcoreclr.so").isFile()) return false;
        try {
            String[] list = context.getAssets().list(ASSETS);
            return list != null && list.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** The framework assemblies, copied out of the APK once per APK install (they must be files). */
    static File frameworkDir(Context context) throws IOException {
        return extracted(context, ASSETS);
    }

    /** Where the build put the startup hook (tools/rimworld17/build_clrhooks.sh). */
    static final String HOOKS_ASSETS = "dotnet/hooks";

    /**
     * RimDroidClrHooks.dll, the startup hook standing in for the game runtime's switches that mods
     * need (Unity assembly names, glibc's libc for Harmony); null if this APK has none.
     */
    static File hooksFile(Context context) throws IOException {
        File f = new File(extracted(context, HOOKS_ASSETS), "RimDroidClrHooks.dll");
        return f.isFile() ? f : null;
    }

    /** An assets folder, copied out of the APK into files/ under the same path once per APK install. */
    private static File extracted(Context context, String assets) throws IOException {
        File dir = new File(context.getFilesDir(), assets);
        File stamp = new File(dir, ".stamp");
        long apk = apkStamp(context);
        if (stamp.isFile() && readLong(stamp) == apk && apk != 0) return dir;
        AssetManager am = context.getAssets();
        String[] names = am.list(assets);
        if (names == null) throw new IOException("no " + assets + " in this APK");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        byte[] buf = new byte[1 << 16];
        for (String name : names) {
            try (InputStream in = am.open(assets + "/" + name);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        try (FileOutputStream out = new FileOutputStream(stamp)) {
            out.write(Long.toString(apk).getBytes());
        }
        Log.i(TAG, assets + ": " + names.length + " files extracted to " + dir);
        return dir;
    }

    /**
     * Runtime properties for coreclr_initialize. TRUSTED_PLATFORM_ASSEMBLIES = the stock framework +
     * every game assembly whose name the framework does not already have (the game ships its own,
     * modified copies of the System.* ones).
     */
    public static List<String[]> propertiesFor(Context context, GameInstance gi) throws IOException {
        // Canonical paths throughout: the process's current directory comes back from the kernel as
        // /data/data/<pkg>/..., while Context.getFilesDir() says /data/user/0/<pkg>/... (a symlink to
        // the same place). The engine mounts Assets.zip under one spelling and lists folders under
        // the other, comparing ordinally, so every folder of textures came back empty.
        File fw = frameworkDir(context).getCanonicalFile();
        File game = new File(gi.getGamePath()).getCanonicalFile();
        File hooks = hooksFile(context);
        Set<String> names = new HashSet<>();
        StringBuilder tpa = new StringBuilder();
        File[] fwFiles = fw.listFiles();
        if (fwFiles != null) for (File f : fwFiles) {
            if (!f.getName().endsWith(".dll")) continue;
            names.add(f.getName().toLowerCase(Locale.ROOT));
            if (tpa.length() > 0) tpa.append(':');
            // A file of the same name among the hooks replaces the framework's own
            // (System.Runtime.dll, tools/rimworld17/build_runtime_facade.sh).
            File ours = hooks == null ? null : new File(hooks.getParentFile(), f.getName());
            tpa.append((ours != null && ours.isFile() ? ours.getCanonicalFile() : f).getAbsolutePath());
        }
        int gameCount = 0;
        retargetX64Assemblies(game);
        DotnetPatcher.patchPlatformChecks(game);
        DotnetPatcher.gpuTextureEncoder(game, !bcConversion(gi));
        DotnetPatcher.laxGameAssembly(game);
        patchMods(game);
        File[] gameFiles = game.listFiles();
        if (gameFiles != null) for (File f : gameFiles) {
            String n = f.getName();
            if (!n.endsWith(".dll") || names.contains(n.toLowerCase(Locale.ROOT))) continue;
            tpa.append(':').append(f.getAbsolutePath());
            gameCount++;
        }
        // The startup hook in the TPA too: mods refer to it (AccessChecks' attribute type).
        if (hooks != null) tpa.append(':').append(hooks.getCanonicalPath());
        Log.i(TAG, "TPA: " + names.size() + " framework + " + gameCount + " game assemblies");
        String nativeDirs = context.getApplicationInfo().nativeLibraryDir + ":" + game.getAbsolutePath();
        List<String[]> props = new ArrayList<>();
        props.add(new String[] { "TRUSTED_PLATFORM_ASSEMBLIES", tpa.toString() });
        props.add(new String[] { "APP_PATHS", game.getAbsolutePath() });
        props.add(new String[] { "APP_CONTEXT_BASE_DIRECTORY", game.getAbsolutePath() + "/" });
        props.add(new String[] { "NATIVE_DLL_SEARCH_DIRECTORIES", nativeDirs });
        props.add(new String[] { "RUNTIME_IDENTIFIER", "android-arm64" });
        if (hooks != null) props.add(new String[] { "STARTUP_HOOKS", hooks.getCanonicalPath() });
        // Globalization stays on: RimWorld sets real cultures (en-US and the player's language), which
        // invariant mode refuses ("Only the invariant culture is supported"). .NET for Android uses
        // the system's ICU.
        props.add(new String[] { "System.Runtime.Serialization.EnableUnsafeBinaryFormatterSerialization", "true" });
        return props;
    }

    /**
     * RimWorld.dll and com.rlabrecque.steamworks.net.dll are built for x64 (PE machine AMD64, IL
     * only, no precompiled code): the stock runtime refuses them on ARM ("The assembly architecture
     * is not compatible"); Ludeon's runtime has a switch for that (WaylithClr.LaxAssemblyArchitecture).
     * Their IL is the same on any CPU, so the machine field is set to ARM64, in place, the original
     * kept as .rdorig. Assemblies with precompiled native code (ReadyToRun) are never touched.
     */
    static void retargetX64Assemblies(File game) {
        File[] files = game.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().endsWith(".dll") || !f.isFile()) continue;
            retargetX64(f, true);
        }
    }

    /** An IL-only PE32+ assembly marked AMD64 becomes ARM64 (same length, header only). */
    private static void retargetX64(File f, boolean backup) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
            if (raf.length() < 512) return;
            byte[] head = new byte[4096];
            raf.readFully(head, 0, (int) Math.min(head.length, raf.length()));
            int machine = x64Machine(head);
            if (machine < 0) return;
            if (backup) {
                File orig = new File(f.getPath() + ".rdorig");
                if (!orig.exists()) java.nio.file.Files.copy(f.toPath(), orig.toPath());
            }
            raf.seek(machine);
            raf.write(new byte[] { (byte) 0x64, (byte) 0xAA });        // IMAGE_FILE_MACHINE_ARM64
            Log.i(TAG, "retargeted " + f.getPath() + " x64 -> arm64 (IL only)");
        } catch (IOException e) {
            Log.w(TAG, "cannot retarget " + f.getName() + ": " + e);
        }
    }

    /** {@link #retargetX64(File, boolean)} on an assembly in memory; false if it needs none. */
    static boolean retargetX64(byte[] b) {
        int machine = x64Machine(b);
        if (machine < 0) return false;
        b[machine] = (byte) 0x64;
        b[machine + 1] = (byte) 0xAA;
        return true;
    }

    /** Where the machine field of an IL-only AMD64 PE32+ assembly is, or -1 if it is not one. */
    private static int x64Machine(byte[] head) {
        if (head.length < 512 || head[0] != 'M' || head[1] != 'Z') return -1;
        int pe = le32(head, 0x3c);
        if (pe < 0 || pe + 24 + 240 > head.length) return -1;
        if (le16(head, pe + 4) != 0x8664) return -1;                // AMD64 only
        int opt = pe + 24;
        if (le16(head, opt) != 0x20b) return -1;                    // PE32+
        int cliRva = le32(head, opt + 112 + 14 * 8);
        if (cliRva == 0) return -1;                                 // native, not .NET
        int cli = rvaToOffset(head, pe, cliRva);
        if (cli < 0 || cli + 72 > head.length) return -1;
        int flags = le32(head, cli + 16);
        int managedNative = le32(head, cli + 64);
        if ((flags & 1) == 0 || managedNative != 0) return -1;      // IL only, no ReadyToRun
        return pe + 4;
    }

    /**
     * Mods on RimWorld 1.7, before every launch (idempotent; a re-downloaded mod is handled again):
     * <ul>
     * <li>x64-marked IL-only assemblies (CameraPlus and others) are retargeted like the game's own:
     *   stock .NET refuses them on ARM64 ("assembly architecture is not compatible"); the game's
     *   runtime has a switch for it (WaylithClr.LaxAssemblyArchitecture).</li>
     * <li>MonoMod inside Lib.Harmony (and any MonoMod*.dll) takes the existence of
     *   /system/build.prop as "Android", for which MonoMod.Core has no platform
     *   (PlatformTriple.CreateCurrentSystem: NotImplementedException) — so Harmony could patch
     *   nothing. Android is Linux underneath; with that path renamed in place (same length,
     *   UTF-16 in the #US heap) MonoMod takes its Linux platform. On 1.6 box64 hid the file.</li>
     * <li>Static readonly fields of the mods' own assemblies become writable by reflection, as the
     *   game's runtime allows (DotnetPatcher.laxMetadata; Harmony and MonoMod are left alone).</li>
     * </ul>
     */
    static void patchMods(File game) {
        File mods = new File(game, "Mods");
        if (!mods.isDirectory()) return;
        java.util.ArrayDeque<File> dirs = new java.util.ArrayDeque<>();
        dirs.add(mods);
        int seen = 0;
        while (!dirs.isEmpty()) {
            File[] list = dirs.poll().listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (f.isDirectory()) { dirs.add(f); continue; }
                String n = f.getName();
                if (!n.toLowerCase(Locale.ROOT).endsWith(".dll")) continue;
                seen++;
                retargetX64(f, false);
                if (n.equals("0Harmony.dll") || n.startsWith("MonoMod")) hideAndroidFromMonoMod(f);
                else DotnetPatcher.laxModAssembly(f);
            }
        }
        Log.i(TAG, "mods: " + seen + " assemblies checked");
    }

    private static final byte[] BUILD_PROP = "/system/build.prop".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
    private static final byte[] NO_PATH = "/system/build.none".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);

    private static void hideAndroidFromMonoMod(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            int n = 0;
            outer:
            for (int i = 0; i + BUILD_PROP.length <= b.length; i++) {
                for (int j = 0; j < BUILD_PROP.length; j++) if (b[i + j] != BUILD_PROP[j]) continue outer;
                System.arraycopy(NO_PATH, 0, b, i, NO_PATH.length);
                n++;
                i += BUILD_PROP.length - 1;
            }
            if (n == 0) return;
            java.nio.file.Files.write(f.toPath(), b);
            Log.i(TAG, "MonoMod in " + f.getPath() + " now sees Linux (" + n + ")");
        } catch (IOException e) {
            Log.w(TAG, "cannot patch " + f.getName() + ": " + e);
        }
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private static int le32(byte[] b, int o) { return le16(b, o) | le16(b, o + 2) << 16; }

    private static int rvaToOffset(byte[] b, int pe, int rva) {
        int sections = le16(b, pe + 6), optSize = le16(b, pe + 20), so = pe + 24 + optSize;
        for (int i = 0; i < sections; i++) {
            int s = so + i * 40;
            if (s + 40 > b.length) return -1;
            int vs = le32(b, s + 8), va = le32(b, s + 12), raw = le32(b, s + 20);
            if (rva >= va && rva < va + Math.max(vs, le32(b, s + 16))) return rva - va + raw;
        }
        return -1;
    }

    /** RimWorld's UI scale steps (Options → Interface). */
    private static final float[] UI_SCALES = { 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f };

    /**
     * A phone-sized UI on the first launch: RimWorld 1.7 starts at UI scale 1, which on a 1080-line
     * phone screen is tiny. The largest step that keeps the UI at least 1280x768 virtual pixels goes
     * into the game's Prefs.xml (1.25 on a 1080-line render): RimWorld lays its windows out for 768
     * lines, and at 720 the Needs tab of a colonist with 8+ needs loses its top. The game only greys
     * out bigger steps in its options; it accepts them from the file. Done once per instance, and
     * only over the default 1: a scale the player chose stays.
     */
    public static void defaultUiScale(GameInstance gi, com.rimdroid.InstanceSettings is, int renderW, int renderH) {
        if (is.isUiScaleDefaulted() || renderW <= 0 || renderH <= 0) return;
        float fit = Math.min(renderW / 1280f, renderH / 768f), scale = 1f;
        for (float step : UI_SCALES) if (step <= fit + 1e-3f) scale = step;
        File prefs = new File(new File(gi.getUserDataDir(), "Config"), "Prefs.xml");
        try {
            if (scale > 1f) {
                String value = scale == Math.rint(scale) ? String.valueOf((int) scale) : String.valueOf(scale);
                if (prefs.isFile()) {
                    String xml = new String(java.nio.file.Files.readAllBytes(prefs.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<uiScale>\\s*([0-9.]+)\\s*</uiScale>").matcher(xml);
                    if (m.find()) {
                        if (Float.parseFloat(m.group(1)) == 1f)
                            xml = xml.substring(0, m.start()) + "<uiScale>" + value + "</uiScale>" + xml.substring(m.end());
                    } else if (xml.contains("</PrefsData>")) {
                        xml = xml.replace("</PrefsData>", "  <uiScale>" + value + "</uiScale>\n</PrefsData>");
                    }
                    java.nio.file.Files.write(prefs.toPath(), xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    File dir = prefs.getParentFile();
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                    String xml = "﻿<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<PrefsData>\n  <uiScale>" + value
                            + "</uiScale>\n</PrefsData>\n";
                    java.nio.file.Files.write(prefs.toPath(), xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Log.i(TAG, "UI scale " + value + " for a " + renderW + "x" + renderH + " render");
            }
            is.setUiScaleDefaulted();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot set the UI scale in " + prefs, e);
        }
    }

    public static Intent launchIntent(Context context, GameInstance gi) {
        Intent i = new Intent(context, com.rimdroid.ClrActivity.class);
        i.putExtra(com.rimdroid.ClrActivity.EXTRA_INSTANCE, gi.getName());
        i.putExtra(EXTRA_GPU_BC, TextureCache.gpuBc());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static long apkStamp(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long readLong(File f) {
        try {
            return Long.parseLong(new String(java.nio.file.Files.readAllBytes(f.toPath())).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
