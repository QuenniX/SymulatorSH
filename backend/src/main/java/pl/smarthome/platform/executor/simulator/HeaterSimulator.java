package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Symulator grzejnika elektrycznego.
 * Na razie zachowanie identyczne jak LIGHT - prosty schedule ON/OFF
 */
public class HeaterSimulator extends LightSimulator {

    public HeaterSimulator(DeviceConfig config,
                           long randomSeed,
                           int globalJitterTimeMinutes,
                           int globalJitterPowerPercent) {
        super(adjustDefaultPower(config), randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
    }

    @Override
    public String getDeviceType() {
        return "HEATER";
    }

    /** Heater ma inną domyślną moc niż lampa, jeśli params jest puste. */
    private static DeviceConfig adjustDefaultPower(DeviceConfig original) {
        if (original.getParams() != null && original.getParams().containsKey("power_w")) {
            return original;
        }
        DeviceConfig copy = DeviceConfig.builder()
                .id(original.getId())
                .type(original.getType())
                .params(new java.util.HashMap<>())
                .schedule(original.getSchedule())
                .build();
        copy.getParams().put("power_w", 1500);
        return copy;
    }
}
