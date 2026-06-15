package pl.smarthome.platform.executor.simulator;

import lombok.Getter;
import pl.smarthome.platform.api.dto.DeviceConfig;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Wspólne narzędzia dla wszystkich symulatorów.
 * Obsługuje parsowanie harmonogramu i ekstrakcję parametrów.
 */
public abstract class BaseSimulator implements DeviceSimulator {

    @Getter
    protected final String deviceId;

    protected final DeviceConfig config;

    protected final Random random;

    protected final int globalJitterTimeMinutes;

    protected final int globalJitterPowerPercent;

    protected BaseSimulator(DeviceConfig config,
                            long randomSeed,
                            int globalJitterTimeMinutes,
                            int globalJitterPowerPercent) {
        this.deviceId = config.getId();
        this.config = config;
        this.random = new Random(randomSeed);
        this.globalJitterTimeMinutes = globalJitterTimeMinutes;
        this.globalJitterPowerPercent = globalJitterPowerPercent;
    }

    protected double getDoubleParam(String key, double defaultValue) {
        if (config.getParams() == null) {
            return defaultValue;
        }
        Object v = config.getParams().get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return defaultValue;
    }

    protected int getIntParam(String key, int defaultValue) {
        return (int) getDoubleParam(key, defaultValue);
    }

    protected double applyPowerJitter(double power) {
        if (globalJitterPowerPercent <= 0 || power <= 0) {
            return power;
        }
        double sigma = power * globalJitterPowerPercent / 100.0;
        double noise = random.nextGaussian() * sigma;
        return Math.max(0, power + noise);
    }

    protected int parseTimeToMinutes(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return 0;
        }
        String[] parts = hhmm.split(":");
        if (parts.length != 2) {
            return 0;
        }
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        return hours * 60 + minutes;
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> getScheduleActions() {
        if (config.isScheduleString()) {
            return List.of();
        }
        if (config.getSchedule() instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    protected boolean isAlwaysOn() {
        return "always_on".equalsIgnoreCase(config.getScheduleString());
    }

    protected boolean isAlwaysOff() {
        return "always_off".equalsIgnoreCase(config.getScheduleString());
    }
}
