package com.rimdroid;

/**
 * Detects the device GPU once and applies the matching recommended Vulkan driver to a freshly
 * created instance, so a new instance doesn't start on the System driver (which black-screens many
 * Adreno GPUs). The caller shows {@link Result} to the user and can offer "change in settings".
 */
public final class GpuDriverAdvisor {

    private GpuDriverAdvisor() {}

    public static final class Result {
        public final String gpuName;     // e.g. "Adreno (TM) 610"
        public final String driverLabel; // e.g. "Turnip Adreno 6xx (legacy)"
        public final String driverSo;    // soName written to the instance
        public final boolean applied;    // false if the instance already had an explicit driver
        Result(String gpuName, String driverLabel, String driverSo, boolean applied) {
            this.gpuName = gpuName;
            this.driverLabel = driverLabel;
            this.driverSo = driverSo;
            this.applied = applied;
        }
    }

    /**
     * Query the GPU and store the recommended driver on {@code instanceName} (unless it already has
     * an explicit driver). Runs a small EGL context — call OFF the UI thread.
     */
    public static Result applyRecommendedDriver(String instanceName) {
        GpuInfo gpu = GpuInfo.query();
        String so = gpu.recommendedDriverSo();
        int idx = 0;
        for (int i = 0; i < LauncherPreferences.VULKAN_DRIVERS.size(); i++) {
            if (LauncherPreferences.VULKAN_DRIVERS.get(i).soName.equals(so)) { idx = i; break; }
        }
        String label = LauncherPreferences.VULKAN_DRIVERS.get(idx).label;
        InstanceSettings s = new InstanceSettings(instanceName);
        boolean applied = false;
        if (!s.hasExplicitDriver()) {
            s.setVulkanDriverSo(so);
            applied = true;
        }
        return new Result(gpu.displayName(), label, so, applied);
    }
}
