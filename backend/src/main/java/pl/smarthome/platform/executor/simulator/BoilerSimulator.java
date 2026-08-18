package pl.smarthome.platform.executor.simulator;

import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Symulator elektrycznego podgrzewacza wody (bojlera).
 *
 * Utrzymuje temperaturę wody - grzałka włącza się cyklicznie.
 * Domyślnie: cykl 43 min (duty_cycle 0.17 -> 7 min ON / 36 min OFF).
 * Zachowuje się jak lodówka, ale wyższa moc.
 *
 * Zwykle konfigurowany jako "always_on" - grzeje wodę cały czas.
 *
 * <b>KRYTYCZNE - dlaczego 37 a nie 60:</b> proba pomiaru jest brana co 5 min,
 * od minuty 0. Bojler z cyklem 60 min startuje ON zawsze od minuty 0 kazdej
 * godziny -> faza sampling↔cykl jest DETERMINISTYCZNA i STAŁA, jitter tego
 * nie uśrednia. Efekt: liczba trafionych sampli w godzinie = ceil(onPortion/5)
 * zamiast onPortion/5 -> zawyzenie energii godzinowej do +67% dla malych duty
 * (0.10). Wartosc 43 (wzglednie pierwsza z 5 i z 60) sprawia ze faza dryfuje
 * i blad sam sie usrednia do ~0%.
 *
 * <b>Dlaczego 43 a nie 37:</b> onPortion = round(cycle * duty), wiec przy cyklu 37
 * duty 0.10 i 0.12 daja TE SAMA wartosc 4/37 = 10.8% - profile A i B/E mialy
 * identyczny bojler. Przy 43: 0.10 -> 4/43 = 9.3%, 0.12 -> 5/43 = 11.6%, znow
 * rozroznialne. Faza liczona jest z licznika absolutnego (BaseSimulator.nextCycleTick),
 * bo modulo z minuty doby resetowalo sie o polnocy i zamrazalo tetnienie godzinowe.
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
        // Default 37 (nie 60) - unika aliasingu z probkowaniem 5-min. Patrz javadoc klasy.
        this.cycleLengthMinutes = getIntParam("cycle_length_minutes", 43);
        this.onPortionMinutes = (int) Math.round(cycleLengthMinutes * dutyCycle);
    }

    @Override
    public String getDeviceType() {
        return "BOILER";
    }

    @Override
    public double updatePower(int simulatedMinuteOfDay) {
        // Tick pobieramy ZAWSZE, przed wczesnym returnem - patrz BaseSimulator.nextCycleTick().
        long tick = nextCycleTick();
        if (isAlwaysOff()) {
            return 0;
        }
        int positionInCycle = (int) (tick % cycleLengthMinutes);
        if (positionInCycle < onPortionMinutes) {
            return applyPowerJitter(powerW);
        }
        return 0;
    }
}
