package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Symulator telewizora.
 * Zachowanie identyczne jak LIGHT - stała moc na ON, zero na OFF.
 * Różni się tylko domyślną mocą (~120 W dla współczesnych LED TV).
 */
public class TvSimulator extends LightSimulator {

    public TvSimulator(DeviceConfig config,
                       long randomSeed,
                       int globalJitterTimeMinutes,
                       int globalJitterPowerPercent) {
        super(adjustDefaultPower(config), randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
    }

    @Override
    public String getDeviceType() {
        return "TV";
    }

    /** TV ma inną domyślną moc niż lampa, jeśli params jest puste. */
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
        copy.getParams().put("power_w", 120);
        return copy;
    }
}
