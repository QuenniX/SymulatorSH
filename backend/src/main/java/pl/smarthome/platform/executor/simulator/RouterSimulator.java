package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Symulator routera / punktu dostępowego.
 *
 * Zachowanie identyczne jak LIGHT ze schedule "always_on".
 * Niska stała moc (15 W). Reprezentuje baseload domu - urządzenia
 * 24/7 (router, alarm, zegar, ładowarki stand-by).
 */
public class RouterSimulator extends LightSimulator {

    public RouterSimulator(DeviceConfig config,
                           long randomSeed,
                           int globalJitterTimeMinutes,
                           int globalJitterPowerPercent) {
        super(adjustDefaults(config), randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
    }

    @Override
    public String getDeviceType() {
        return "ROUTER";
    }

    /**
     * Router ma niską domyślną moc i defaultowo always_on jeśli schedule pusty.
     */
    private static DeviceConfig adjustDefaults(DeviceConfig original) {
        boolean hasPower = original.getParams() != null && original.getParams().containsKey("power_w");
        boolean hasSchedule = original.getSchedule() != null;
        if (hasPower && hasSchedule) {
            return original;
        }
        DeviceConfig copy = DeviceConfig.builder()
                .id(original.getId())
                .type(original.getType())
                .params(new java.util.HashMap<>(
                        original.getParams() != null ? original.getParams() : java.util.Map.of()))
                .schedule(hasSchedule ? original.getSchedule() : "always_on")
                .build();
        if (!hasPower) copy.getParams().put("power_w", 15);
        return copy;
    }
}
