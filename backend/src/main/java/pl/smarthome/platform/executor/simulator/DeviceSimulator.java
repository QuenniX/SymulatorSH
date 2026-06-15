package pl.smarthome.platform.executor.simulator;

/**
 * Wspólny interfejs symulatorów urządzeń.
 *
 * Implementacja oblicza chwilową moc pobieraną przez urządzenie
 * w danym momencie symulowanej doby. Wartość zwracana w watach.
 *
 * Metoda wywoływana jest przez TestRunner w każdym kroku symulacji.
 */
public interface DeviceSimulator {

    /** Identyfikator urządzenia z pola id w JSON-ie konfiguracji. */
    String getDeviceId();

    /** Typ urządzenia (LIGHT, REFRIGERATOR, WASHER, HEATER, ...). */
    String getDeviceType();

    /**
     * Aktualizuje stan wewnętrzny i zwraca chwilową moc pobieraną.
     *
     * @param simulatedMinuteOfDay numer minuty od początku symulowanej doby (0..1439)
     * @return moc chwilowa w watach (zawsze >= 0)
     */
    double updatePower(int simulatedMinuteOfDay);
}
