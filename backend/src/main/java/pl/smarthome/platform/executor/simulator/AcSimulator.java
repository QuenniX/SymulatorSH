package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

import java.util.Map;
import java.util.TreeMap;

/**
 * Symulator klimatyzacji.
 *
 * Sterowana harmonogramem (ON/OFF wg schedule). W trakcie stanu ON
 * pracuje cyklicznie (kompresor), utrzymując zadaną temperaturę.
 * Domyślnie: duty_cycle 0.5, cycle 43 min (43 jest wzglednie pierwsza z 5 i z 60,
 * co eliminuje aliasing z 5-minutowym probkowaniem - patrz javadoc BoilerSimulator).
 *
 * Poza godzinami ON wg harmonogramu - wyłączona (0 W).
 */
public class AcSimulator extends BaseSimulator {

    private final double powerW;
    private final double dutyCycle;
    private final int cycleLengthMinutes;
    private final int onPortionMinutes;

    private final TreeMap<Integer, Boolean> stateTimeline = new TreeMap<>();

    public AcSimulator(DeviceConfig config,
                       long randomSeed,
                       int globalJitterTimeMinutes,
                       int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.powerW = getDoubleParam("power_w", 1000);
        this.dutyCycle = Math.max(0.05, Math.min(0.95, getDoubleParam("duty_cycle", 0.5)));
        this.cycleLengthMinutes = getIntParam("cycle_length_minutes", 43);
        this.onPortionMinutes = (int) Math.round(cycleLengthMinutes * dutyCycle);
        buildTimeline();
    }

    @Override
    public String getDeviceType() {
        return "AC";
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
        long tick = nextCycleTick();
        Boolean userWantsOn = stateTimeline.floorEntry(simulatedMinuteOfDay).getValue();
        if (userWantsOn == null || !userWantsOn) {
            return 0;
        }
        // Duty cycle wewnątrz stanu ON - faza z licznika absolutnego, nie z minuty doby
        int positionInCycle = (int) (tick % cycleLengthMinutes);
        if (positionInCycle < onPortionMinutes) {
            return applyPowerJitter(powerW);
        }
        return 0;
    }
}
