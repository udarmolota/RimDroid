package com.rimdroid;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the phone's GPU and recommends a bundled Vulkan driver for it.
 *
 * <p>Unlike a vendor-only check (Qualcomm vs MediaTek via /proc/cpuinfo), we spin up a throwaway
 * offscreen EGL/GLES context and read {@code GL_RENDERER} from the phone's OWN driver — e.g.
 * "Adreno (TM) 830", "Mali-G720" — which carries the exact Adreno SERIES number. That lets us map
 * to the right Turnip revision (6xx legacy vs 7xx vs 8xx), since Turnip rev matters per series.
 */
public final class GpuInfo {

    /** Raw GL_RENDERER from the phone's GLES driver (e.g. "Adreno (TM) 830"); null if unavailable. */
    @Nullable public final String renderer;
    /** Raw GL_VENDOR (e.g. "Qualcomm"); null if unavailable. */
    @Nullable public final String vendor;
    /** Parsed Adreno series number (6/7/8/…) or 0 if not an Adreno / unknown. */
    public final int adrenoSeries;
    /** Full Adreno model number (e.g. 644, 735, 830) or 0 if not an Adreno / unknown. */
    public final int adrenoModel;

    private GpuInfo(@Nullable String renderer, @Nullable String vendor, int adrenoSeries, int adrenoModel) {
        this.renderer = renderer;
        this.vendor = vendor;
        this.adrenoSeries = adrenoSeries;
        this.adrenoModel = adrenoModel;
    }

    /** Query the GPU. Cheap (a 1x1 pbuffer context); call off the very first frame to be safe. */
    @NonNull
    public static GpuInfo query() {
        String r = null, v = null;
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
                int[] cfgAttr = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_NONE };
                EGLConfig[] cfgs = new EGLConfig[1];
                int[] num = new int[1];
                if (EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0) && num[0] > 0) {
                    int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
                    ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                    int[] surfAttr = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
                    surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], surfAttr, 0);
                    if (ctx != EGL14.EGL_NO_CONTEXT && surf != EGL14.EGL_NO_SURFACE
                            && EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                        r = GLES20.glGetString(GLES20.GL_RENDERER);
                        v = GLES20.glGetString(GLES20.GL_VENDOR);
                    }
                }
            }
        } catch (Throwable ignored) {
            // any EGL failure → fall back to a null renderer (recommend System)
        } finally {
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignored) {}
        }
        int model = parseAdrenoModel(r);
        int series = model > 0 ? (model / (model >= 1000 ? 1000 : 100)) : 0;  // 644→6, 735→7, 830→8
        return new GpuInfo(r, v, series, model);
    }

    /** Extract the full Adreno model number, e.g. "Adreno (TM) 830" → 830, "Adreno (TM) 644" → 644. */
    private static int parseAdrenoModel(@Nullable String renderer) {
        if (renderer == null) return 0;
        if (!renderer.toLowerCase().contains("adreno")) return 0;
        Matcher m = Pattern.compile("(\\d{3,4})").matcher(renderer);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /**
     * The recommended driver's .so name (a member of {@link LauncherPreferences#VULKAN_DRIVERS}).
     * Adreno picks the matching Turnip; everything else (Mali, PowerVR, Xclipse, unknown) → "" =
     * System (the phone's own Vulkan driver), which is the safe default for non-Adreno GPUs.
     */
    @NonNull
    public String recommendedDriverSo() {
        switch (adrenoSeries) {
            case 8: return "libvulkan_freedreno.v25.so";   // Adreno 8xx (830/840) — tested best
            case 7: return "libvulkan.ad07XX_regular.so";  // Adreno 7xx
            case 6:
                // a6xx is split: NEWER high a6xx (>=630: 630/640/642L/644/650/660/680/690) need a MODERN
                // Turnip (Mesa 25) — the legacy ad06XX (old Mesa) HANGS Zink at context creation on them
                // (field-confirmed: Adreno 644 / Snapdragon 7 Gen 1 / Huawei MatePad 11.5 2023). The legacy
                // build is only for OLD budget a6xx (<=620: a610/a612/a619), which v25 in turn black-screens.
                return (adrenoModel >= 630) ? "libvulkan_freedreno.v25.so" : "vulkan.ad06XX.so";
            default: return LauncherPreferences.SYSTEM_VULKAN_DRIVER_SO; // "" System
        }
    }

    /** Human-readable GPU name for UI/toasts, e.g. "Adreno (TM) 830" or "unknown GPU". */
    @NonNull
    public String displayName() {
        return renderer != null ? renderer : "unknown GPU";
    }
}
