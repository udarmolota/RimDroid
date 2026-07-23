package com.rimdroid;

import android.content.SharedPreferences;

import com.rimdroid.LauncherPreferences.Renderer;
import com.rimdroid.LauncherPreferences.VulkanDriverOption;

import java.util.List;

/**
 * Per-instance launch settings: renderer, Vulkan driver, debug, interpreter mode. Each instance
 * keeps its own (e.g. one instance on the System driver, another on Turnip; debug only on a test
 * instance). Stored in the shared prefs under an {@code inst:<name>:} prefix.
 *
 * Defaults fall back to the corresponding GLOBAL {@link LauncherPreferences} value, so existing
 * single-instance users keep their current setup on first run after the per-instance migration, and
 * brand-new instances start from the same sane defaults (ZINK_ZFA + System driver + debug off).
 *
 * Render scale and the on-screen controls layout remain GLOBAL (read by GameActivity / the controls
 * editor, which aren't instance-scoped) — by design.
 */
public class InstanceSettings {

    private final SharedPreferences p;
    private final String pfx;
    private final LauncherPreferences global;

    public InstanceSettings(String instanceName) {
        global = LauncherPreferences.requireSingleton();
        p = global.getSharedPrefs();
        pfx = "inst:" + instanceName + ":";
    }

    // --- Renderer ---
    public Renderer getRenderer() {
        String def = global.getRenderer().name();
        try { return Renderer.valueOf(p.getString(pfx + "renderer", def)); }
        catch (Exception e) { return Renderer.ZINK_ZFA; }
    }

    public void setRenderer(Renderer r) {
        p.edit().putString(pfx + "renderer", r.name()).apply();
    }

    // --- Vulkan driver (.so file name; "" = System / phone driver) ---
    public String getVulkanDriverSo() {
        return p.getString(pfx + "driver_so", global.getVulkanDriverSo());
    }

    public void setVulkanDriverSo(String soName) {
        p.edit().putString(pfx + "driver_so", soName).apply();
    }

    /** True if this instance has its OWN driver choice stored (vs. inheriting the global default). */
    public boolean hasExplicitDriver() {
        return p.contains(pfx + "driver_so");
    }

    /** Index of the selected driver in {@link LauncherPreferences#VULKAN_DRIVERS} (0 if unknown). */
    public int getVulkanDriverIndex() {
        String so = getVulkanDriverSo();
        List<VulkanDriverOption> drivers = LauncherPreferences.VULKAN_DRIVERS;
        for (int i = 0; i < drivers.size(); i++) {
            if (drivers.get(i).soName.equals(so)) return i;
        }
        return 0;
    }

    // --- Debug ---
    // Debug is a throwaway diagnostic (extra box64 logging), NOT something to inherit: new
    // instances always start with it OFF, regardless of the (now-vestigial) global debug flag.
    // Inheriting global.isDebug() used to turn debug ON for every new instance when the global
    // flag was left enabled from the old global-settings design — which slowed launches.
    public boolean isDebug() {
        return p.getBoolean(pfx + "debug", false);
    }

    public void setDebug(boolean v) {
        p.edit().putBoolean(pfx + "debug", v).apply();
    }

    // --- Drag-to-pan (move the camera by dragging the map with a finger). Default ON. ---
    public boolean isDragPan() {
        return p.getBoolean(pfx + "drag_pan", true);
    }

    public void setDragPan(boolean v) {
        p.edit().putBoolean(pfx + "drag_pan", v).apply();
    }

    // --- Mirrored (180°-rotated) landscape. Still a FIXED orientation, just the opposite one:
    // a USB-C gamepad cradle holds the phone in one physical pose, which may be the reverse of ours,
    // making the game unplayable for those users. Deliberately NOT sensor-based — free rotation
    // re-exposes the flip-induced resolution drift (each flip fires surfaceChanged and could leave
    // the game stuck in a stretched menu), which is why GameActivity pins a single landscape. ---
    public boolean isReverseLandscape() {
        return p.getBoolean(pfx + "reverse_landscape", false);
    }

    public void setReverseLandscape(boolean v) {
        p.edit().putBoolean(pfx + "reverse_landscape", v).apply();
    }

    // --- A fixed monitor resolution instead of filling the screen, letterboxed with black margins.
    // Asked for by players coming from PC emulators, who wanted 720p specifically. Both modes keep
    // 720 lines (our readability floor) and differ only in shape: 16:9 suits ordinary phones, 4:3
    // suits near-square foldables, where 16:9 would waste a third of the screen. Fewer pixels than
    // any device-relative preset, and the margins give the on-screen buttons somewhere to sit that
    // isn't on top of the map. Overrides the render-scale setting while on. Default OFF. ---
    public static final int FIXED_NONE = 0, FIXED_720_16_9 = 1, FIXED_720_4_3 = 2;

    public int getFixedResMode() {
        return p.getInt(pfx + "fixed_res", FIXED_NONE);
    }

    public void setFixedResMode(int mode) {
        p.edit().putInt(pfx + "fixed_res", mode).apply();
    }

    // --- Haptic feedback: light vibration tick on on-screen button presses. Default OFF. ---
    public boolean isHapticFeedback() {
        return p.getBoolean(pfx + "haptic", global.isHapticFeedback());
    }

    public void setHapticFeedback(boolean v) {
        p.edit().putBoolean(pfx + "haptic", v).apply();
    }

    // --- Interpreter mode (BOX64_DYNAREC=0 diagnostic; pref key kept for back-compat as "interpreter") ---
    public boolean isInterpreter() {
        return p.getBoolean(pfx + "interpreter", global.isStrictBarriers());
    }

    public void setInterpreter(boolean v) {
        p.edit().putBoolean(pfx + "interpreter", v).apply();
    }

    // --- Compatibility mode: box64 dynarec tuning that dodges the deep "won't launch past the loading
    // dots / black screen" bug on affected devices (Adreno 610/725, weak-Vulkan Mali). Discovered via a
    // tester: sets BOX64_DYNAREC_WEAKBARRIER=2 + BOX64_DYNAREC_X87DOUBLE=1 in GameLauncher (reshapes the
    // FP/barrier codegen so the bad pattern is avoided). Default OFF — devices that already launch keep the
    // safer/faster defaults; turn ON only if the game won't start. (A workaround, not the root fix.)
    public boolean isCompatibilityMode() {
        return p.getBoolean(pfx + "compat_mode", false);
    }

    public void setCompatibilityMode(boolean v) {
        p.edit().putBoolean(pfx + "compat_mode", v).apply();
    }

    // --- Extra env vars (KEY=VALUE, space-separated). Per-instance, falls back to the global value. ---
    // Power-user / diagnostic knob applied last in GameLauncher, so it can OVERRIDE the box64 defaults.
    // E.g. "BOX64_DYNAREC_ALIGNED_ATOMICS=1" (Mali/Cortex save-corruption test) or
    // "BOX64_DYNAREC_STRONGMEM=2" (FPS A/B). Lets us test box64 knobs without a rebuild per variant.
    public String getEnvVars() {
        return p.getString(pfx + "env_vars", global.getEnvVars());
    }

    public void setEnvVars(String v) {
        if (v == null || v.trim().isEmpty()) p.edit().remove(pfx + "env_vars").apply();
        else p.edit().putString(pfx + "env_vars", v.trim()).apply();
    }

    // --- Render scale (per-instance; the device floor stays a global static) ---
    public int getRenderScalePercent() {
        int v = p.getInt(pfx + "render_scale_pct", global.getRenderScalePercent());
        return Math.max(LauncherPreferences.RENDER_SCALE_ABS_MIN, Math.min(100, v));
    }

    public void setRenderScalePercent(int pct) {
        p.edit().putInt(pfx + "render_scale_pct",
                Math.max(LauncherPreferences.RENDER_SCALE_ABS_MIN, Math.min(100, pct))).apply();
    }

    /** Stored scale raised to the per-device floor (>=1280x720), as a 0..1 fraction. */
    public float getEffectiveRenderScale(int surfaceW, int surfaceH) {
        int eff = Math.max(getRenderScalePercent(), LauncherPreferences.minRenderScalePercent(surfaceW, surfaceH));
        return Math.min(100, eff) / 100f;
    }

    // --- On-screen controls layout (per-instance JSON) ---
    public String getControlsJson() {
        return p.getString(pfx + "controls", global.getControlsJson());
    }

    public void setControlsJson(String json) {
        p.edit().putString(pfx + "controls", json).apply();
    }

    public void clearControlsJson() {
        p.edit().remove(pfx + "controls").apply();
    }

    /** Remove all stored keys for an instance (call when the instance is deleted). */
    public static void delete(String instanceName) {
        SharedPreferences p = LauncherPreferences.requireSingleton().getSharedPrefs();
        String pfx = "inst:" + instanceName + ":";
        p.edit()
                .remove(pfx + "renderer")
                .remove(pfx + "driver_so")
                .remove(pfx + "debug")
                .remove(pfx + "interpreter")
                .remove(pfx + "compat_mode")
                .remove(pfx + "env_vars")
                .remove(pfx + "haptic")
                .remove(pfx + "reverse_landscape")
                .remove(pfx + "fixed_res")
                .remove(pfx + "render_scale_pct")
                .remove(pfx + "controls")
                .apply();
    }
}

