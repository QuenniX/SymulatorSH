package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

import java.util.Map;
import java.util.TreeMap;

/**
 * Symulator piekarnika elektrycznego.
 *
 * Po komendzie ON piekarnik pracuje przez cycle_minutes, ale grzałka
 * pulsuje (heat_on_minutes ON / heat_off_minutes OFF) żeby utrzymać
 * zadaną temperaturę. To odwzorowuje realną fizykę termostatu.
 *
 * Domyślnie: cykl 60 min, grzałka 5 min ON / 3 min OFF, moc 2500 W.
 * Wykres wygląda impulsowo - to celowe.
 */
public class OvenSimulator extends BaseSimulator {

    private final double powerW;
    private final int cycleMinutes;
    private final int heatOnMinutes;
    private final int heatOffMinutes;
    private final int heatCycleLength;

    private Integer cycleStartMinute = null;
    private boolean userTurnedOff = false;

    private final TreeMap<Integer, String> userActions = new TreeMap<>();

    public OvenSimulator(DeviceConfig config,
                         long randomSeed,
                         int globalJitterTimeMinutes,
                         int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.powerW = getDoubleParam("power_w", 2500);
        this.cycleMinutes = getIntParam("cycle_minutes", 60);
        this.heatOnMinutes = getIntParam("heat_on_minutes", 5);
        this.heatOffMinutes = getIntParam("heat_off_minutes", 3);
        this.heatCycleLength = heatOnMinutes + heatOffMinutes;
        buildActions();
    }

    @Override
    public String getDeviceType() {
        return "OVEN";
    }

    private void buildActions() {
        if (isAlwaysOn()) {
            userActions.put(0, "ON");
            return;
        }
        for (Map<String, Object> action : getScheduleActions()) {
            String act = ((String) action.getOrDefault("action", "ON")).toUpperCase();
            String at = (String) action.get("at");
            int baseMinute = parseTimeToMinutes(at);

            Integer jitterTime = (Integer) action.get("jitter_time_minutes");
            int jitter = jitterTime != null ? jitterTime : globalJitterTimeMinutes;
            int actualMinute = baseMinute + (jitter > 0 ? random.nextInt(2 * jitter + 1) - jitter : 0);
            actualMinute = Math.max(0, Math.min(1439, actualMinute));

            userActions.put(actualMinute, act);
        }
    }

    @Override
    public double updatePower(int simulatedMinuteOfDay) {
        String action = userActions.get(simulatedMinuteOfDay);
        if (action != null) {
            if ("ON".equals(action)) {
                cycleStartMinute = simulatedMinuteOfDay;
                userTurnedOff = false;
            } else if ("OFF".equals(action)) {
                userTurnedOff = true;
            }
        }

        if (userTurnedOff || cycleStartMinute == null) {
            return 0;
        }

        int elapsed = simulatedMinuteOfDay - cycleStartMinute;
        if (elapsed < 0 || elapsed >= cycleMinutes) {
            return 0;
        }

        // W trakcie cyklu - pulsuje wg heatCycleLength
        int heatPosition = elapsed % heatCycleLength;
        if (heatPosition < heatOnMinutes) {
            return applyPowerJitter(powerW);
        }
        return 0;
    }
}
