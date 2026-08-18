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

    /**
     * Licznik minut symulowanych od poczatku testu (nie od poczatku doby).
     *
     * <p><b>Po co:</b> urzadzenia cykliczne (BOILER/AC/REFRIGERATOR) liczyly faze jako
     * {@code simulatedMinuteOfDay % cycleLength}, co resetowalo ja o polnocy. Efekt:
     * kazda z 30 dob miala IDENTYCZNY wzorzec fazowy wzgledem 5-minutowej siatki
     * probkowania, wiec tetnienie godzinowe (do +/-35% dla malych duty) nie usredialo
     * sie przy liczeniu profilu dobowego E_h, tylko sie w nim utrwalalo.</p>
     *
     * <p>Licznik absolutny sprawia, ze faza dryfuje z doby na dobe
     * ({@code 1440 mod 43 = 20}), wiec po 30 dobach kazda godzina doby widzi wiele
     * roznych faz i blad usrednia sie do ~0.</p>
     *
     * <p><b>Kontrakt:</b> {@code updatePower} jest wolane dokladnie raz na minute
     * symulowana dla kazdego urzadzenia (TestRunner - obie galezie petli, emisyjna
     * i nieemisyjna). Metoda musi byc wolana bezwarunkowo, PRZED jakimkolwiek
     * wczesnym returnem, inaczej licznik rozjedzie sie z czasem symulowanym.</p>
     */
    private long cycleTick = 0;

    /** Zwraca biezacy tick i inkrementuje licznik. Wolac raz na wywolanie updatePower. */
    protected long nextCycleTick() {
        return cycleTick++;
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
