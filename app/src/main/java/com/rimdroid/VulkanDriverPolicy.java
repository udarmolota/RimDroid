package com.rimdroid;

import androidx.annotation.NonNull;

/** Resolves the saved Vulkan-driver preference into the driver that is safe for this device. */
public final class VulkanDriverPolicy {

    private VulkanDriverPolicy() {}

    public static final class Decision {
        @NonNull public final String configuredSo;
        @NonNull public final String effectiveSo;
        @NonNull public final String reason;

        Decision(@NonNull String configuredSo, @NonNull String effectiveSo,
                 @NonNull String reason) {
            this.configuredSo = configuredSo;
            this.effectiveSo = effectiveSo;
            this.reason = reason;
        }
    }

    @NonNull
    /** Backwards-compatible overload: treats an empty driver as "not explicitly chosen". */
    public static Decision resolve(String configuredSo, @NonNull GpuInfo gpu,
                                   boolean autoSelectAdrenoTurnip) {
        return resolve(configuredSo, gpu, autoSelectAdrenoTurnip, false);
    }

    /**
     * @param explicitSystem the user deliberately picked "System" in the driver spinner (a stored,
     *     empty driver choice) — as opposed to an instance that simply has no driver set yet. When
     *     true we NEVER auto-substitute a Turnip: on Adreno 640 every bundled Turnip crashes at
     *     zfaCreateContext, so silently overriding an explicit System pick trapped those devices
     *     with no working option (field-confirmed on Galaxy S10+ / Poco X3 Pro, both Adreno 640).
     */
    public static Decision resolve(String configuredSo, @NonNull GpuInfo gpu,
                                   boolean autoSelectAdrenoTurnip, boolean explicitSystem) {
        String configured = normalize(configuredSo);
        String candidate = configured;
        String prefix = "";

        if (!isKnownOption(configured)) {
            candidate = LauncherPreferences.SYSTEM_VULKAN_DRIVER_SO;
            prefix = "configured driver is no longer bundled; ";
        }

        // Every bundled non-custom ICD currently shipped by RimDroid is Turnip/Freedreno and
        // therefore Adreno-only. A positively identified non-Adreno device must use its system
        // Vulkan driver. Preserve an explicit choice if probing failed, and preserve the imported
        // custom slot because it may contain a vendor-appropriate ICD.
        if (isBundledAdrenoOnlyDriver(candidate) && gpu.isKnownGpu() && !gpu.isAdreno()) {
            return new Decision(configured, LauncherPreferences.SYSTEM_VULKAN_DRIVER_SO,
                    prefix + "bundled Turnip is Adreno-only; detected " + gpu.displayName()
                            + "; using System");
        }

        // Auto-pick a Turnip only when the instance has no driver chosen yet. An explicit System
        // pick is honoured as-is — the player's choice wins over the recommendation.
        if (autoSelectAdrenoTurnip && candidate.isEmpty() && !explicitSystem) {
            String recommended = gpu.recommendedDriverSo();
            if (!recommended.isEmpty()) {
                return new Decision(configured, recommended,
                        prefix + "detected " + gpu.displayName() + "; selected matching Turnip");
            }
            return new Decision(configured, candidate,
                    prefix + (gpu.isKnownGpu()
                            ? "detected non-Adreno GPU; using System"
                            : "GPU probe unavailable; using System"));
        }

        return new Decision(configured, candidate,
                prefix + (candidate.isEmpty()
                        ? (explicitSystem ? "honouring explicit System pick" : "using configured System driver")
                        : "keeping configured driver"));
    }

    public static boolean isKnownOption(String soName) {
        String normalized = normalize(soName);
        for (LauncherPreferences.VulkanDriverOption option
                : LauncherPreferences.VULKAN_DRIVERS) {
            if (option.soName.equals(normalized)) return true;
        }
        return false;
    }

    public static boolean isBundledAdrenoOnlyDriver(String soName) {
        String normalized = normalize(soName);
        return !normalized.isEmpty()
                && !C.deps.CUSTOM_DRIVER_FILENAME.equals(normalized)
                && isKnownOption(normalized);
    }

    @NonNull
    public static String displayName(String soName) {
        String normalized = normalize(soName);
        if (normalized.isEmpty()) return "System (phone driver)";
        for (LauncherPreferences.VulkanDriverOption option
                : LauncherPreferences.VULKAN_DRIVERS) {
            if (option.soName.equals(normalized)) {
                return option.label + " [" + normalized + "]";
            }
        }
        return normalized;
    }

    @NonNull
    private static String normalize(String soName) {
        return soName == null ? LauncherPreferences.SYSTEM_VULKAN_DRIVER_SO : soName.trim();
    }
}
