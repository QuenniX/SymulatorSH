package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

import java.util.Map;
import java.util.TreeMap;

/**
 * Symulator pojedynczego źródła światła.
 * Przyjmuje listę akcji ON/OFF z konkretnymi godzinami i ustala stan
 * dla każdej minuty doby na podstawie ostatniej wykonanej akcji.
 */
public class LightSimulator extends BaseSimulator {

    private final double powerW;

    /** Mapa minuta -> stan (true/false). */
    private final TreeMap<Integer, Boolean> stateTimeline = new TreeMap<>();

    public LightSimulator(DeviceConfig config,
                          long randomSeed,
                          int globalJitterTimeMinutes,
                          int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.powerW = getDoubleParam("power_w", 60);
        buildTimeline();
    }

    @Override
    public String getDeviceType() {
        return "LIGHT";
    }

    private void buildTimeline() {
        if (isAlwaysOn()) {
            stateTimeline.put(0, true);
            return;
        }
        if (isAlwaysOff()) {
            stateTimeline.put(0, false);
            return;
        }

        // Domyślnie OFF od początku doby
        stateTimeline.put(0, false);

        for (Map<String, Object> action : getScheduleActions()) {
            String act = (String) action.getOrDefault("action", "ON");
            String at = (String) action.get("at");
            int baseMinute = parseTimeToMinutes(at);

            Integer jitterTime = (Integer) action.get("jitter_time_minutes");
            int jitter = jitterTime != null ? jitterTime : globalJitterTimeMinutes;
            int actualMinute = baseMinute + (jitter > 0 ? random.nextInt(2 * jitter + 1) - jitter : 0);
            actualMinute = Math.max(0, Math.min(1439, actualMinute));

            boolean state = switch (act.toUpperCase()) {
                case "ON" -> true;
                case "OFF" -> false;
                case "TOGGLE" -> !stateTimeline.floorEntry(actualMinute).getValue();
                default -> stateTimeline.floorEntry(actualMinute).getValue();
            };
            stateTimeline.put(actualMinute, state);

            // duration_minutes - po tym czasie wykonujemy akcję przeciwną
            Integer duration = (Integer) action.get("duration_minutes");
            if (duration != null && duration > 0) {
                int offMinute = Math.min(1439, actualMinute + duration);
                stateTimeline.put(offMinute, !state);
            }
        }
    }

    @Override
    public double updatePower(int simulatedMinuteOfDay) {
        Boolean state = stateTimeline.floorEntry(simulatedMinuteOfDay).getValue();
        if (state == null || !state) {
            return 0;
        }
        return applyPowerJitter(powerW);
    }
}
