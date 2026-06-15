package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Symulator lodówki.
 *
 * Lodówka pracuje cyklicznie - kompresor włącza się okresowo,
 * niezależnie od harmonogramu użytkownika.
 * Domyślnie: 15 minut ON / 22 minuty OFF (duty cycle 0.4).
 */
public class RefrigeratorSimulator extends BaseSimulator {

    private final double powerW;
    private final double dutyCycle;
    private final int cycleLengthMinutes;
    private final int onPortionMinutes;

    public RefrigeratorSimulator(DeviceConfig config,
                                 long randomSeed,
                                 int globalJitterTimeMinutes,
                                 int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.powerW = getDoubleParam("power_w", 150);
        this.dutyCycle = Math.max(0.05, Math.min(0.95, getDoubleParam("duty_cycle", 0.4)));
        this.cycleLengthMinutes = getIntParam("cycle_length_minutes", 37);
        this.onPortionMinutes = (int) Math.round(cycleLengthMinutes * dutyCycle);
    }

    @Override
    public String getDeviceType() {
        return "REFRIGERATOR";
    }

    @Override
    public double updatePower(int simulatedMinuteOfDay) {
        if (isAlwaysOff()) {
            return 0;
        }
        int positionInCycle = simulatedMinuteOfDay % cycleLengthMinutes;
        if (positionInCycle < onPortionMinutes) {
            return applyPowerJitter(powerW);
        }
        return 0;
    }
}
