package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

import java.util.Map;
import java.util.TreeMap;

/**
 * Symulator pralki.
 *
 * Po komendzie ON pralka uruchamia cykl prania trwający cycle_minutes,
 * po czym sama się wyłącza. Komenda OFF w trakcie cyklu przerywa go.
 */
public class WasherSimulator extends BaseSimulator {

    private final double powerW;
    private final int cycleMinutes;

    /** Minuta startu aktywnego cyklu albo null jeśli idle. */
    private Integer cycleStartMinute = null;
    private boolean userTurnedOff = false;

    /** Plan akcji użytkownika - mapa minuta -> ON/OFF/TOGGLE. */
    private final TreeMap<Integer, String> userActions = new TreeMap<>();

    public WasherSimulator(DeviceConfig config,
                           long randomSeed,
                           int globalJitterTimeMinutes,
                           int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.powerW = getDoubleParam("power_w", 2000);
        this.cycleMinutes = getIntParam("cycle_minutes", 60);
        buildActions();
    }

    @Override
    public String getDeviceType() {
        return "WASHER";
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
        // Sprawdź akcję użytkownika w bieżącej minucie
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
        return applyPowerJitter(powerW);
    }
}
