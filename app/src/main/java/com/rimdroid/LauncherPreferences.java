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
        ZINK_ZFA("libGL.so.1"),       // Mesa Zink via ZFA window
        ZINK_OSMESA("libGL.so.1");    // Mesa Zink via OSMesa (fallback)

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
        new VulkanDriverOption("libvulkan_freedreno.so",     "Freedreno 7xx/8xx (Default)"),
        new VulkanDriverOption("libvulkan_freedreno_8xx.so", "Freedreno 8xx (newer)"),
        new VulkanDriverOption("libvulkan_freedreno.v25.so", "Turnip Adreno830/840 v25"),
        new VulkanDriverOption("libvulkan_freedreno_840.so", "Turnip Adreno 830/840"),
        new VulkanDriverOption("libvulkan.ad07XX.so",        "Turnip Adreno 7xx"),
        new VulkanDriverOption(SYSTEM_VULKAN_DRIVER_SO,      "System (phone driver) — experimental")
    );

    public static final String DEFAULT_VULKAN_DRIVER_SO = "libvulkan_freedreno.so";

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

    // --- Render scale (UI size) ---
    // RimWorld scales its UI with resolution, so rendering at a LOWER fraction of
    // the native surface makes on-screen UI BIGGER (and is lighter on the GPU).
    // RimWorld's UI has a ~1280x720 minimum, so below ~67% on a 1080-tall surface the
    // layout overflows (the colony START button falls off the bottom). Clamp 67..100,
    // default 72.
    public static final int RENDER_SCALE_MIN = 67;

    public int getRenderScalePercent() {
        int v = prefs.getInt("render_scale_pct", 72);
        return Math.max(RENDER_SCALE_MIN, Math.min(100, v));
    }

    public void setRenderScalePercent(int pct) {
        prefs.edit().putInt("render_scale_pct", Math.max(RENDER_SCALE_MIN, Math.min(100, pct))).apply();
    }

    public float getRenderScale() { return getRenderScalePercent() / 100f; }

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

    // --- Custom env vars (advanced) ---

    @Nullable
    public String getEnvVars() {
        return prefs.getString("env_vars", null);
    }
}
