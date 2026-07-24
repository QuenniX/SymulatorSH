package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Symulator czajnika elektrycznego.
 *
 * Mechanika identyczna jak WASHER (event-driven cykl), ale bardzo krótki
 * i wysoki: domyślnie 3 minuty przy 2000 W.
 * Charakterystyczny ostry pik mocy w krótkim czasie.
 */
public class KettleSimulator extends WasherSimulator {

    public KettleSimulator(DeviceConfig config,
                           long randomSeed,
                           int globalJitterTimeMinutes,
                           int globalJitterPowerPercent) {
        super(adjustDefaults(config), randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
    }

    @Override
    public String getDeviceType() {
        return "KETTLE";
    }

    /** Czajnik ma inne domyślne parametry niż pralka. */
    private static DeviceConfig adjustDefaults(DeviceConfig original) {
        boolean hasPower = original.getParams() != null && original.getParams().containsKey("power_w");
        boolean hasCycle = original.getParams() != null && original.getParams().containsKey("cycle_minutes");
        if (hasPower && hasCycle) {
            return original;
        }
        DeviceConfig copy = DeviceConfig.builder()
                .id(original.getId())
                .type(original.getType())
                .params(new java.util.HashMap<>(
                        original.getParams() != null ? original.getParams() : java.util.Map.of()))
                .schedule(original.getSchedule())
                .build();
        if (!hasPower) copy.getParams().put("power_w", 2000);
        if (!hasCycle) copy.getParams().put("cycle_minutes", 3);
        return copy;
    }
}
