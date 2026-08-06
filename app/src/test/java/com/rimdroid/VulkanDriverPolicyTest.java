package com.rimdroid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VulkanDriverPolicyTest {

    private static GpuInfo gpu(String renderer, String vendor, int model) {
        return new GpuInfo(renderer, vendor, model > 0 ? model / 100 : 0, model);
    }

    @Test
    public void maliSystemStaysSystem() {
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "", gpu("Mali-G720", "ARM", 0), true);
        assertEquals("", d.effectiveSo);
    }

    @Test
    public void maliStoredTurnipIsClampedToSystem() {
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "libvulkan_freedreno.v25.so", gpu("Mali-G720", "ARM", 0), true);
        assertEquals("", d.effectiveSo);
    }

    @Test
    public void maliCustomDriverIsPreserved() {
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                C.deps.CUSTOM_DRIVER_FILENAME, gpu("Mali-G720", "ARM", 0), true);
        assertEquals(C.deps.CUSTOM_DRIVER_FILENAME, d.effectiveSo);
    }

    @Test
    public void adrenoSystemGetsMatchingTurnipForOneSix() {
        // No driver chosen yet (explicitSystem=false) → auto-pick the matching Turnip.
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "", gpu("Adreno (TM) 830", "Qualcomm", 830), true);
        assertEquals("libvulkan_freedreno.v25.so", d.effectiveSo);
    }

    @Test
    public void explicitSystemPickIsHonouredOnAdreno() {
        // Player deliberately chose "System" on an Adreno device under 1.6 — must NOT be overridden
        // with a Turnip (Adreno 640 crashes on every bundled Turnip; System is its only path).
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "", gpu("Adreno (TM) 640", "Qualcomm", 640), true, true);
        assertEquals("", d.effectiveSo);
    }

    @Test
    public void adrenoAutoPickStillWorksWhenNotExplicit() {
        // Same device, but no explicit choice → auto-pick still applies (regression guard).
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "", gpu("Adreno (TM) 640", "Qualcomm", 640), true, false);
        assertEquals("libvulkan_freedreno_7xx_new.so", d.effectiveSo);
    }

    @Test
    public void explicitAdrenoDriverIsPreserved() {
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "libvulkan.ad07XX_regular.so", gpu("Adreno (TM) 740", "Qualcomm", 740), true);
        assertEquals("libvulkan.ad07XX_regular.so", d.effectiveSo);
    }

    @Test
    public void failedProbeDoesNotOverrideExplicitChoice() {
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "libvulkan.ad07XX_regular.so", gpu(null, null, 0), true);
        assertEquals("libvulkan.ad07XX_regular.so", d.effectiveSo);
    }

    @Test
    public void removedDriverStillGetsRecommendedReplacementOnAdreno() {
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "retired-driver.so", gpu("Adreno (TM) 740", "Qualcomm", 740), true);
        assertEquals("libvulkan.ad07XX_regular.so", d.effectiveSo);
    }

    @Test
    public void removedDriverFallsBackToSystemOnMali() {
        VulkanDriverPolicy.Decision d = VulkanDriverPolicy.resolve(
                "retired-driver.so", gpu("Mali-G720", "ARM", 0), true);
        assertEquals("", d.effectiveSo);
    }

    @Test
    public void adrenoParserAnchorsModelAfterName() {
        assertEquals(830, GpuInfo.parseAdrenoModel("Adreno (TM) 830"));
        assertEquals(740, GpuInfo.parseAdrenoModel(
                "ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)"));
        assertEquals(0, GpuInfo.parseAdrenoModel("Mali-G720"));
        assertEquals(0, GpuInfo.parseAdrenoModel("Adreno OpenGL ES 3.2 V@512"));
    }
}
