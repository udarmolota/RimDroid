package com.rimdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

public class LauncherPreferences {

    // Must match names used in rimdroid.c / rimdroid_globals.h
    public enum Renderer {
        GL4ES("libGL.so.1"),
        ZINK_ZFA("libGL.so.1"),       // Mesa Zink via ZFA window (GPU, Vulkan)
        ZINK_OSMESA("libGL.so.1"),    // Mesa Zink via OSMesa (unused fallback)
        SOFTPIPE("libGL.so.1");       // Mesa softpipe (CPU) via OSMesa + blit — works on any GPU

        public final String libName;
        Renderer(String libName) { this.libName = libName; }
    }

    public enum VulkanDriver {
        SYSTEM(null),
        CUSTOM("custom_driver.so"),
        TURNIP_ADRENO("libvulkan_freedreno.so"),
        MALEOON("libvulkan_maleoon.so");

        @Nullable public final String libName;
        VulkanDriver(@Nullable String libName) { this.libName = libName; }
    }

    /** One selectable Vulkan/Turnip driver: the .so file name in the deps dir + a UI label. */
    public static final class VulkanDriverOption {
        public final String soName;   // file in dependencies/android-arm64-v8a/
        public final String label;    // shown in the settings spinner
        public VulkanDriverOption(String soName, String label) {
            this.soName = soName; this.label = label;
        }
        @NonNull @Override public String toString() { return label; }
    }

    // The drivers bundled in assets/bundles/libs.tar.xz (android-arm64-v8a/).
    // The first string (soName) must match the archive member; the second is the
    // UI label shown in the settings spinner.
    // soName "" is the special "System" option: do NOT inject a bundled Turnip ICD,
    // let the phone's own Vulkan driver handle it (experimental; may work on Mali /
    // Dimensity, or with ANGLE enabled in the phone's developer options).
    public static final String SYSTEM_VULKAN_DRIVER_SO = "";

    public static final List<VulkanDriverOption> VULKAN_DRIVERS = Arrays.asList(
        new VulkanDriverOption(SYSTEM_VULKAN_DRIVER_SO,      "System (phone driver) — default"),
        new VulkanDriverOption("libvulkan_freedreno.v25.so", "Turnip Adreno830/840 v25"),
        new VulkanDriverOption("libvulkan_freedreno_8xx.so", "Freedreno 8xx (newer)"),
        new VulkanDriverOption("libvulkan_freedreno_840.so", "Turnip Adreno 830/840"),
        new VulkanDriverOption("libvulkan_freedreno.so",     "Freedreno 7xx/8xx"),
        // ad07XX has env vars baked in for an anti-flicker fix; _regular is the plain build.
        new VulkanDriverOption("libvulkan.ad07XX.so",         "Turnip Adreno 7xx (anti-flicker)"),
        new VulkanDriverOption("libvulkan.ad07XX_regular.so", "Turnip Adreno 7xx (regular)"),
        // Older Turnip revision (Mesa ~23/24, 2024-03) for legacy Adreno 6xx (e.g. Snapdragon 685
        // = Adreno 610). The newer v25 freedreno builds black-screen on these; this is the build
        // that worked on old Adreno in Zomdroid.
        new VulkanDriverOption("vulkan.ad06XX.so",           "Turnip Adreno 6xx (legacy)"),
        // User-supplied driver imported via App settings → "Import custom Vulkan driver".
        // The .so is stored in the deps dir as custom_driver.so; pick this to use it.
        new VulkanDriverOption(C.deps.CUSTOM_DRIVER_FILENAME, "Custom driver (imported)")
    );

    // Default = the phone's own Vulkan driver: works on any GPU (Adreno=Qualcomm driver,
    // Mali/MediaTek=Mali driver). The bundled Turnip "freedreno" base hangs on the newest
    // Adreno (a8xx), so it's no longer the default; advanced users can pick a Turnip variant.
    public static final String DEFAULT_VULKAN_DRIVER_SO = SYSTEM_VULKAN_DRIVER_SO;

    private final SharedPreferences prefs;
    private static LauncherPreferences singleton;

    private LauncherPreferences(Context applicationContext) {
        prefs = applicationContext.getSharedPreferences(C.shprefs.NAME, Context.MODE_PRIVATE);
    }

    public static void init(Context applicationContext) {
        singleton = new LauncherPreferences(applicationContext);
    }

    @Nullable
    public static LauncherPreferences getSingleton() { return singleton; }

    @NonNull
    public static LauncherPreferences requireSingleton() {
        if (singleton == null) throw new RuntimeException("LauncherPreferences is not initialized");
        return singleton;
    }

    public SharedPreferences getSharedPrefs() { return prefs; }

    // --- Dependencies ---

    public boolean areDependenciesInstalled() {
        return prefs.getBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, false);
    }

    public void setDependenciesInstalled(boolean value) {
        prefs.edit().putBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, value).apply();
    }

    // --- Renderer ---

    public Renderer getRenderer() {
        String name = prefs.getString("renderer", Renderer.ZINK_ZFA.name());
        try { return Renderer.valueOf(name); } catch (Exception e) { return Renderer.ZINK_ZFA; }
    }

    public void setRenderer(Renderer renderer) {
        prefs.edit().putString("renderer", renderer.name()).apply();
    }

    // --- Vulkan driver ---

    public VulkanDriver getVulkanDriver() {
        String name = prefs.getString("vulkan_driver", VulkanDriver.SYSTEM.name());
        try { return VulkanDriver.valueOf(name); } catch (Exception e) { return VulkanDriver.SYSTEM; }
    }

    public void setVulkanDriver(VulkanDriver driver) {
        prefs.edit().putString("vulkan_driver", driver.name()).apply();
    }

    /** Selected driver .so file name (used by the ZINK_ZFA path). */
    public String getVulkanDriverSo() {
        return prefs.getString("vulkan_driver_so", DEFAULT_VULKAN_DRIVER_SO);
    }

    public void setVulkanDriverSo(String soName) {
        prefs.edit().putString("vulkan_driver_so", soName).apply();
    }

    /** Index of the currently selected driver in {@link #VULKAN_DRIVERS} (0 if unknown). */
    public int getVulkanDriverIndex() {
        String so = getVulkanDriverSo();
        for (int i = 0; i < VULKAN_DRIVERS.size(); i++) {
            if (VULKAN_DRIVERS.get(i).soName.equals(so)) return i;
        }
        return 0;
    }

    // --- Render scale (UI size / GPU load) ---
    // RimWorld scales its UI with resolution: rendering at a LOWER fraction of the
    // native surface makes the on-screen UI BIGGER and is lighter on the GPU.
    // RimWorld's UI has a ~1280x720 minimum — below that the layout overflows (the
    // colony START button falls off the bottom). The minimum USABLE scale therefore
    // depends on the screen: a 1080p panel floors at ~67% (720/1080), a 1440p panel
    // can go down to ~50% (720/1440). A fixed percentage floor would force a high-res
    // "potato" to render needlessly many pixels (→ 5 FPS), so the real floor is
    // computed per device by minRenderScalePercent(); the stored value is only clamped
    // to a sane absolute range. Default 72.
    public static final int RENDER_SCALE_ABS_MIN = 25;

    public int getRenderScalePercent() {
        int v = prefs.getInt("render_scale_pct", 72);
        return Math.max(RENDER_SCALE_ABS_MIN, Math.min(100, v));
    }

    public void setRenderScalePercent(int pct) {
        prefs.edit().putInt("render_scale_pct",
                Math.max(RENDER_SCALE_ABS_MIN, Math.min(100, pct))).apply();
    }

    public float getRenderScale() { return getRenderScalePercent() / 100f; }

    /**
     * Lowest render-scale percent that still yields a &gt;=1280x720 internal resolution
     * for the given physical surface, so high-res phones can scale further down than
     * low-res ones. Pass landscape dimensions (the larger value as width). Clamped to
     * [RENDER_SCALE_ABS_MIN, 100].
     */
    public static int minRenderScalePercent(int surfaceW, int surfaceH) {
        if (surfaceW <= 0 || surfaceH <= 0) return 67;   // safe fallback (1080p floor)
        double need = Math.max(1280.0 / surfaceW, 720.0 / surfaceH);
        int pct = (int) Math.ceil(need * 100.0);
        return Math.max(RENDER_SCALE_ABS_MIN, Math.min(100, pct));
    }

    /**
     * The render scale actually applied for a given physical surface: the stored value
     * raised to the per-device floor (so RimWorld's UI never drops below 1280x720).
     * Used by both the game and the controls editor so the two always agree.
     */
    public float getEffectiveRenderScale(int surfaceW, int surfaceH) {
        int eff = Math.max(getRenderScalePercent(), minRenderScalePercent(surfaceW, surfaceH));
        return Math.min(100, eff) / 100f;
    }

    // --- On-screen controls layout (JSON, see com.rimdroid.input) ---

    @Nullable
    public String getControlsJson() {
        return prefs.getString("input_controls", null);
    }

    public void setControlsJson(String json) {
        prefs.edit().putString("input_controls", json).apply();
    }

    public void clearControlsJson() {
        prefs.edit().remove("input_controls").apply();
    }

    // --- Theme mode (System / Light / Dark) ---
    // Stores an AppCompatDelegate.MODE_NIGHT_* constant; applied in RimDroidApplication.onCreate.

    public int getThemeMode() {
        return prefs.getInt("theme_mode",
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt("theme_mode", mode).apply();
    }

    // --- Last instance ---

    public String getLastInstanceName() {
        return prefs.getString("last_instance", "");
    }

    public void setLastInstanceName(String name) {
        prefs.edit().putString("last_instance", name).apply();
    }

    // --- Debug ---

    public boolean isDebug() {
        return prefs.getBoolean("debug_mode", false);
    }

    // --- Interpreter mode (test) ---
    // When on, GameLauncher sets BOX64_DYNAREC=0 (disable the dynarec, interpret x86_64).
    // VERY slow — a one-off DECISIVE diagnostic for the save corruption on MediaTek/Cortex:
    // pawns serialize as empty <li/> (colonists vanish on reload). If the interpreter saves
    // them correctly → dynarec codegen bug; if still empty → box64 wrapper/atomic emulation.
    // (Pref key kept as "strict_barriers" for back-compat; earlier WEAKBARRIER=0 and DF=0
    // levers both did NOT fix the save.) HIDE this toggle before any public release.

    public boolean isStrictBarriers() {
        return prefs.getBoolean("strict_barriers", false);
    }

    // --- Custom env vars (advanced) ---

    @Nullable
    public String getEnvVars() {
        return prefs.getString("env_vars", null);
    }
}
