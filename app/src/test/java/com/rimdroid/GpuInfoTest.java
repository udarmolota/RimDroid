package com.rimdroid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Parsing of GL_RENDERER into an Adreno model number.
 *
 * <p>This is what decides whether a phone counts as an Adreno at all, and therefore whether the bundled
 * Turnip drivers may be used. Getting it wrong is silent and expensive: a mis-parsed Adreno is treated as
 * a foreign GPU, the driver policy replaces the player's explicit Turnip pick with the phone's stock
 * driver, and the settings screen keeps showing the pick that never took effect. The VulkanDriverPolicy
 * tests all build a GpuInfo with the model number already supplied, so only these cases cover the parse.
 */
public class GpuInfoTest {

    @Test
    public void plainModelNumbersParse() {
        assertEquals(830, GpuInfo.parseAdrenoModel("Adreno (TM) 830"));
        assertEquals(740, GpuInfo.parseAdrenoModel("Adreno (TM) 740"));
        assertEquals(644, GpuInfo.parseAdrenoModel("Adreno (TM) 644"));
        assertEquals(610, GpuInfo.parseAdrenoModel("Adreno (TM) 610"));
        assertEquals(830, GpuInfo.parseAdrenoModel("Adreno 830"));
    }

    /** Qualcomm's "L" parts: the whole reason this test exists (Samsung A52s / Snapdragon 778G). */
    @Test
    public void letterSuffixedModelsParse() {
        assertEquals(642, GpuInfo.parseAdrenoModel("Adreno (TM) 642L"));
        assertEquals(643, GpuInfo.parseAdrenoModel("Adreno (TM) 643L"));
        assertEquals(619, GpuInfo.parseAdrenoModel("Adreno (TM) 619L"));
    }

    @Test
    public void nonAdrenoAndUnparseableStayZero() {
        assertEquals(0, GpuInfo.parseAdrenoModel("Mali-G610"));
        assertEquals(0, GpuInfo.parseAdrenoModel("Xclipse 940"));
        assertEquals(0, GpuInfo.parseAdrenoModel(null));
        assertEquals(0, GpuInfo.parseAdrenoModel(""));
        // Turnip sometimes renames a 642L as "Adreno 7c+ Gen 3" — no model number to take.
        assertEquals(0, GpuInfo.parseAdrenoModel("Adreno 7c+ Gen 3"));
    }
}
