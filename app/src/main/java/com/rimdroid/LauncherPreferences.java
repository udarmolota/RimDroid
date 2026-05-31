package com.rimdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
