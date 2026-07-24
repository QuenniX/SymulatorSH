package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Symulator elektrycznego podgrzewacza wody (bojlera).
 *
 * Utrzymuje temperaturę wody - grzałka włącza się cyklicznie.
 * Domyślnie: 10 min ON / 50 min OFF (duty_cycle 0.17, cycle 60 min).
 * Zachowuje się jak lodówka, ale dłuższy cykl i wyższa moc.
 *
 * Zwykle konfigurowany jako "always_on" - grzeje wodę cały czas.
 */
public class BoilerSimulator extends BaseSimulator {

    private final double powerW;
    private final double dutyCycle;
    private final int cycleLengthMinutes;
    private final int onPortionMinutes;

    public BoilerSimulator(DeviceConfig config,
                           long randomSeed,
                           int globalJitterTimeMinutes,
                           int globalJitterPowerPercent) {
        super(config, randomSeed, globalJitterTimeMinutes, globalJitterPowerPercent);
        this.powerW = getDoubleParam("power_w", 2000);
        this.dutyCycle = Math.max(0.05, Math.min(0.95, getDoubleParam("duty_cycle", 0.17)));
        this.cycleLengthMinutes = getIntParam("cycle_length_minutes", 60);
        this.onPortionMinutes = (int) Math.round(cycleLengthMinutes * dutyCycle);
    }

    @Override
    public String getDeviceType() {
        return "BOILER";
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
