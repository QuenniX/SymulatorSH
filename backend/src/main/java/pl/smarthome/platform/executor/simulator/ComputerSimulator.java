package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

import java.util.Map;
import java.util.TreeMap;

/**
 * Symulator komputera stacjonarnego.
 *
 * Sterowany harmonogramem ON/OFF. W stanie ON pobiera baseload (idle_power_w)
 * z losowymi skokami do burst_power_w (np. renderowanie, gra).
 * Skoki trwają burst_length_minutes co średnio burst_interval_minutes.
 */
public class ComputerSimulator extends BaseSimulator {

    private final double idlePowerW;
    private final double burstPowerW;
    private final int burstLengthMinutes;
    private final int burstIntervalMinutes;

    private final TreeMap<Integer, Boolean> stateTimeline = new TreeMap<>();

    /** Kiedy zaczyna się kolejny burst (obliczane losowo). */
    private Integer nextBurstStart = null;
    private Integer currentBurstEnd = null;

    public ComputerSimulator(DeviceConfig config,
                             long randomSeed,
                             int globalJitterTimeMinutes,
                             int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.idlePowerW = getDoubleParam("idle_power_w", 100);
        this.burstPowerW = getDoubleParam("burst_power_w", 300);
        this.burstLengthMinutes = getIntParam("burst_length_minutes", 5);
        this.burstIntervalMinutes = getIntParam("burst_interval_minutes", 20);
        buildTimeline();
    }

    @Override
    public String getDeviceType() {
        return "COMPUTER";
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
            // Reset burst state gdy komputer wyłączony
            nextBurstStart = null;
            currentBurstEnd = null;
            return 0;
        }

        // Zaplanuj pierwszy burst jeśli jeszcze nie było
        if (nextBurstStart == null) {
            nextBurstStart = simulatedMinuteOfDay + random.nextInt(Math.max(1, burstIntervalMinutes));
        }

        // Rozpoczęcie burstu?
        if (currentBurstEnd == null && simulatedMinuteOfDay >= nextBurstStart) {
            currentBurstEnd = simulatedMinuteOfDay + burstLengthMinutes;
            nextBurstStart = currentBurstEnd + random.nextInt(Math.max(1, burstIntervalMinutes));
        }

        // W trakcie burstu?
        if (currentBurstEnd != null && simulatedMinuteOfDay < currentBurstEnd) {
            return applyPowerJitter(burstPowerW);
        }

        // Koniec burstu
        if (currentBurstEnd != null && simulatedMinuteOfDay >= currentBurstEnd) {
            currentBurstEnd = null;
        }

        return applyPowerJitter(idlePowerW);
    }
}
