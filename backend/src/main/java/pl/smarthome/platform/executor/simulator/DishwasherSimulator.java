package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

import java.util.Map;
import java.util.TreeMap;

/**
 * Symulator zmywarki.
 *
 * Po komendzie ON zmywarka pracuje przez cycle_minutes, ale z fazami
 * o różnej mocy pobieranej:
 *   - nagrzewanie: heat_phase_minutes na pełnej mocy (~1800 W)
 *   - mycie: wash_phase_minutes na niskiej mocy (~200 W, tylko pompa)
 *   - suszenie: dry_phase_minutes na wysokiej mocy (~1500 W)
 * Łącznie ~90 minut, wykres wygląda charakterystycznie schodkowo.
 */
public class DishwasherSimulator extends BaseSimulator {

    private final double heatPowerW;
    private final double washPowerW;
    private final double dryPowerW;
    private final int heatPhaseMinutes;
    private final int washPhaseMinutes;
    private final int dryPhaseMinutes;
    private final int totalCycleMinutes;

    private Integer cycleStartMinute = null;
    private boolean userTurnedOff = false;

    private final TreeMap<Integer, String> userActions = new TreeMap<>();

    public DishwasherSimulator(DeviceConfig config,
                               long randomSeed,
                               int globalJitterTimeMinutes,
                               int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.heatPowerW = getDoubleParam("heat_power_w", 1800);
        this.washPowerW = getDoubleParam("wash_power_w", 200);
        this.dryPowerW = getDoubleParam("dry_power_w", 1500);
        this.heatPhaseMinutes = getIntParam("heat_phase_minutes", 10);
        this.washPhaseMinutes = getIntParam("wash_phase_minutes", 60);
        this.dryPhaseMinutes = getIntParam("dry_phase_minutes", 20);
        this.totalCycleMinutes = heatPhaseMinutes + washPhaseMinutes + dryPhaseMinutes;
        buildActions();
    }

    @Override
    public String getDeviceType() {
        return "DISHWASHER";
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
        if (elapsed < 0 || elapsed >= totalCycleMinutes) {
            return 0;
        }

        // Faza 1: nagrzewanie
        if (elapsed < heatPhaseMinutes) {
            return applyPowerJitter(heatPowerW);
        }
        // Faza 2: mycie
        if (elapsed < heatPhaseMinutes + washPhaseMinutes) {
            return applyPowerJitter(washPowerW);
        }
        // Faza 3: suszenie
        return applyPowerJitter(dryPowerW);
    }
}
