package pl.smarthome.platform.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.smarthome.platform.api.dto.DeviceConfig;
import pl.smarthome.platform.api.dto.TestConfig;
import pl.smarthome.platform.domain.TestEntity;
import pl.smarthome.platform.domain.TestStatus;
import pl.smarthome.platform.executor.simulator.DeviceSimulator;
import pl.smarthome.platform.executor.simulator.SimulatorFactory;
import pl.smarthome.platform.mqtt.MqttPublisher;
import pl.smarthome.platform.repository.TestRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pojedynczy bieg testu.
 *
 * Wczytuje konfigurację z bazy, tworzy symulatory dla każdego urządzenia,
 * a następnie w pętli (po jednym kroku symulowanej minuty) odpytuje każdy
 * symulator o bieżącą moc i publikuje na MQTT.
 *
 * Krok rzeczywisty = (60_000 ms / speed_factor). Przy speed_factor=720
 * jedna minuta symulowana zajmuje ~83 ms, a dzień - ~2 minuty.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestRunner {

    private final TestRepository testRepository;
    private final SimulatorFactory simulatorFactory;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    /**
     * Cache Caffeine z wartosciami hourlyEnergy - trzeba go czyscic po zakonczeniu
     * testu zeby CostSection od razu pokazywala swieze dane, a nie 5-minutowy TTL.
     */
    private final CacheManager cacheManager;

    /** Usuwa wpis z cache "hourlyEnergy" dla danego testu. Sanity fix po zakonczeniu testu. */
    private void evictHourlyEnergyCache(UUID testId) {
        Cache cache = cacheManager.getCache("hourlyEnergy");
        if (cache != null) {
            cache.evict(testId);
            log.debug("Wyewiktowano cache 'hourlyEnergy' dla testu {}", testId);
        }
    }

    /**
     * Uruchamia test. UWAGA: bez @Transactional na calej metodzie!
     * Dla dlugich testow (>5min) Neon Postgres zabija idle-in-transaction.
     * Zamiast tego uzywamy krotkich transakcji per zmiana statusu (metody @Transactional
     * ponizej), a executeSimulation() dziala OUTSIDE transakcji Postgres.
     */
    public void run(UUID testId) {
        // Krok 1: pobierz config (krotka transakcja)
        Optional<TestEntity> initial = testRepository.findById(testId);
        if (initial.isEmpty()) {
            log.error("TestRunner: brak rekordu dla testu {}", testId);
            return;
        }
        String configJson = initial.get().getConfigJson();

        // Krok 2: oznacz jako RUNNING (krotka transakcja + retry)
        markRunningWithRetry(testId);

        try {
            TestConfig config = objectMapper.readValue(configJson, TestConfig.class);
            log.info("Test {} '{}' startuje: {} dni, speed_factor={}, urządzeń={}",
                    testId, config.getName(), config.getDurationDays(),
                    config.getSpeedFactor(), config.getDevices().size());

            // Krok 3: symulacja BEZ transakcji Postgres (moze trwac dziesiatki minut)
            executeSimulation(testId, config);

            // Krok 4: oznacz jako COMPLETED (krotka transakcja + retry)
            markCompletedWithRetry(testId);
            // Wyewiktuj cache profilu godzinowego - po zakonczeniu testu chcemy
            // pokazac aktualne dane, nie 5-minutowy TTL sprzed konca testu.
            evictHourlyEnergyCache(testId);
            log.info("Test {} ZAKOŃCZONY pomyślnie", testId);

        } catch (Exception e) {
            log.error("Test {} zakończony błędem", testId, e);
            markFailedWithRetry(testId, e);
            // Nawet dla FAILED wyewiktuj cache - moze byc czesciowy profil w bazie
            evictHourlyEnergyCache(testId);
        }
    }

    /** Retry save 3x z 2s przerwami - Neon Postgres czasem zrywa idle connections. */
    private void markRunningWithRetry(UUID testId) {
        retrySave(3, () -> {
            TestEntity e = testRepository.findById(testId).orElseThrow();
            e.setStatus(TestStatus.RUNNING);
            e.setStartedAt(Instant.now());
            testRepository.save(e);
        });
    }

    private void markCompletedWithRetry(UUID testId) {
        retrySave(5, () -> {   // wazniejszy krok - 5 prob
            TestEntity e = testRepository.findById(testId).orElseThrow();
            e.setStatus(TestStatus.COMPLETED);
            e.setFinishedAt(Instant.now());
            testRepository.save(e);
        });
    }

    private void markFailedWithRetry(UUID testId, Exception cause) {
        retrySave(3, () -> {
            TestEntity e = testRepository.findById(testId).orElseThrow();
            e.setStatus(TestStatus.FAILED);
            e.setFinishedAt(Instant.now());
            e.setErrorMessage(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            testRepository.save(e);
        });
    }

    private void retrySave(int maxAttempts, Runnable action) {
        RuntimeException last = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                action.run();
                return;
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("Postgres save attempt {}/{} failed: {}", i, maxAttempts, ex.getMessage());
                if (i < maxAttempts) {
                    try { Thread.sleep(2000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("Postgres save FAILED po {} probach", maxAttempts, last);
    }

    private void executeSimulation(UUID testId, TestConfig config) throws InterruptedException {
        int speedFactor = config.getSpeedFactor();
        int durationDays = config.getDurationDays();
        int totalMinutes = durationDays * 1440;

        // Emisja co N minut symulowanych (default 5) - zmniejsza wolumen writes do InfluxDB
        int emitEveryN = config.getEmitEveryNMinutes() != null
                ? config.getEmitEveryNMinutes() : 5;
        if (emitEveryN < 1) emitEveryN = 1;

        // Krok rzeczywisty na jedną minutę symulowaną
        long stepMs = Math.max(1, 60_000L / speedFactor);

        int globalJitterTime = config.getJitter() != null
                ? config.getJitter().getGlobalTimeMinutes() : 0;
        int globalJitterPower = config.getJitter() != null
                ? config.getJitter().getGlobalPowerPercent() : 0;

        long seed = testId.getMostSignificantBits() ^ testId.getLeastSignificantBits();
        List<DeviceSimulator> simulators = new ArrayList<>();
        for (DeviceConfig dc : config.getDevices()) {
            simulators.add(simulatorFactory.create(dc, seed + dc.getId().hashCode(),
                    globalJitterTime, globalJitterPower));
        }

        log.info("Test {}: emisja pomiarow co {} min sym (totalMinutes={}, ~{} emisji)",
                testId, emitEveryN, totalMinutes, totalMinutes / emitEveryN);

        // Punkt startu w czasie rzeczywistym - wszystkie pomiary maja timestampy
        // liczone od tego momentu jako "start_ms + minuta_sym * krok". Dzieki temu
        // wykres z osia symulowana zawsze pokazuje dokladnie durationDays * 1440 minut,
        // bez rozjazdu przez overhead MQTT/InfluxDB (wczesniej bral System.currentTimeMillis()
        // co przy speedFactor=720 dawalo np. D32 zamiast D30).
        final long testStartMs = System.currentTimeMillis();

        for (int minute = 0; minute < totalMinutes; minute++) {
            int minuteOfDay = minute % 1440;

            // Emit pomiar tylko co N minut symulowanych (zmniejsza obciazenie InfluxDB)
            if (minute % emitEveryN == 0) {
                // Syntetyczny timestamp - idealny wallclock symulowany, bez overheadu petli
                long realTimeMs = testStartMs + (long) minute * 60_000L / speedFactor;
                for (DeviceSimulator sim : simulators) {
                    double power = sim.updatePower(minuteOfDay);
                    mqttPublisher.publishPower(testId, sim.getDeviceId(), power, realTimeMs);
                }
            } else {
                // W minutach bez emisji nadal aktualizujemy stan symulatorow (zeby liczyly cykle)
                for (DeviceSimulator sim : simulators) {
                    sim.updatePower(minuteOfDay);
                }
            }

            Thread.sleep(stepMs);

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Test przerwany");
            }
        }
    }
}
