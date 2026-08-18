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
import pl.smarthome.platform.influx.InfluxWriter;
import pl.smarthome.platform.mqtt.MqttPublisher;
import pl.smarthome.platform.repository.TestRepository;
import pl.smarthome.platform.service.StreamEventPublisher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
    /** Potrzebny zeby wymusic flush bufora zapisu po zakonczeniu symulacji. */
    private final InfluxWriter influxWriter;
    private final ObjectMapper objectMapper;
    /**
     * Cache Caffeine z wartosciami hourlyEnergy - trzeba go czyscic po zakonczeniu
     * testu zeby CostSection od razu pokazywala swieze dane, a nie 5-minutowy TTL.
     */
    private final CacheManager cacheManager;
    /**
     * Publikuje eventy SSE do klientow subskrybujacych stream tego testu.
     * Wywolywane przy zmianie statusu (QUEUED->RUNNING->COMPLETED) i co 25% postepu.
     */
    private final StreamEventPublisher streamEventPublisher;

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
            long droppedBefore = mqttPublisher.getDroppedPublishes();
            long errorsBefore = influxWriter.getWriteErrors();
            executeSimulation(testId, config);

            // Krok 3.5: domkniecie potoku pomiarowego.
            // MQTT->InfluxWriter jest asynchroniczne i batchowane, wiec bez odczekania
            // i flushu ostatnie kilkaset punktow moze jeszcze siedziec w buforze, gdy
            // klient odpyta /costs. Raportujemy tez straty - inaczej niekompletny test
            // wyglada dokladnie tak samo jak kompletny.
            Thread.sleep(2000L);
            influxWriter.flush();
            long dropped = mqttPublisher.getDroppedPublishes() - droppedBefore;
            long writeErrors = influxWriter.getWriteErrors() - errorsBefore;
            if (dropped > 0 || writeErrors > 0) {
                log.error("Test {}: POTOK POMIAROWY NIEKOMPLETNY - porzucone publikacje MQTT: {}, "
                        + "bledy zapisu InfluxDB: {}. Wynik tego testu jest niewiarygodny.",
                        testId, dropped, writeErrors);
            } else {
                log.info("Test {}: potok pomiarowy czysty (0 porzuconych publikacji, 0 bledow zapisu)", testId);
            }

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
        // SSE: powiadom subskrybentow ze test startuje
        streamEventPublisher.publishStatusChange(testId, TestStatus.RUNNING, "Test startuje");
    }

    private void markCompletedWithRetry(UUID testId) {
        retrySave(5, () -> {   // wazniejszy krok - 5 prob
            TestEntity e = testRepository.findById(testId).orElseThrow();
            e.setStatus(TestStatus.COMPLETED);
            e.setFinishedAt(Instant.now());
            testRepository.save(e);
        });
        // SSE: powiadom ze test skonczyl sie sukcesem
        streamEventPublisher.publishStatusChange(testId, TestStatus.COMPLETED, "Test ukonczony pomyslnie");
    }

    private void markFailedWithRetry(UUID testId, Exception cause) {
        retrySave(3, () -> {
            TestEntity e = testRepository.findById(testId).orElseThrow();
            e.setStatus(TestStatus.FAILED);
            e.setFinishedAt(Instant.now());
            e.setErrorMessage(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            testRepository.save(e);
        });
        // SSE: powiadom ze test padl
        streamEventPublisher.publishStatusChange(testId, TestStatus.FAILED,
                "Blad: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
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

        // KLUCZOWA POPRAWKA METODOLOGICZNA (bug znaleziony przy analiza_wyniki.py):
        // Timestampy pomiarow musza byc rozciagniete na CALE 30 dni symulowanych,
        // nie skupione w 1h real time. Przesuwamy okres 30 dni WSTECZ od startu testu,
        // zeby CostCalculator znalazl ceny RDN historyczne (a nie przyszle ktorych PSE nie ma).
        //
        // Przyklad: test startuje 2026-08-06 21:00, symuluje 30 dni.
        //   - PRZED FIX: wszystkie pomiary maja timestamp 21:00-22:00 tego samego dnia (1h real)
        //     -> agregacja hourly zwracala 1-2 punkty zamiast 720 -> analiza kosztow ZUPELNIE bledna
        //   - PO FIX: pomiary rozciagniete 2026-07-07 21:00 -> 2026-08-06 21:00 (30 dni sim)
        //     -> agregacja hourly zwraca 720 unikatowych godzin -> pelny profil dobowy
        //
        // POPRAWKA #2 (znaleziona przy recenzji metodologicznej):
        // simTimeStart MUSI byc uciety do POCZATKU DOBY w strefie Europe/Warsaw.
        // W przeciwnym wypadku minuteOfDay=0 (pierwsza minuta harmonogramu urzadzen)
        // trafia na godzine startu testu, a nie na polnoc. Efekt: urzadzenie
        // skonfigurowane na 19:00 (wieczorny szczyt cen) laduje w Influx z timestampem
        // (startTest_h + 19h) mod 24 -> np. test startowal 20:57, wieczor konfiguracji
        // trafia na 15:57 nastepnej doby -> wycena idzie z dolka fotowoltaicznego
        // zamiast z wieczornego szczytu. Przy roznych momentach uruchomienia partii
        // KAZDY test dostawal inne, losowe przesuniecie.
        // Fix: uciac (testStartMs - 30d) do polnocy w Europe/Warsaw.
        //
        // POPRAWKA #3 (retencja InfluxDB): ucinanie do polnocy przesuwa poczatek okna
        // WSTECZ o maksymalnie 24 h. Przy 30 dniach symulacji i 30-dniowej retencji
        // bucketu (plan darmowy InfluxDB Cloud) najstarsze punkty ladowaly wtedy PRZED
        // granica retencji i byly odrzucane przez serwer:
        //   HTTP 400: "observed timestamp ... is outside of the retention period,
        //              minimum acceptable timestamp is ..."
        // Objaw: przez pierwsze ~1 min czasu rzeczywistego test pokazywal 0 pomiarow
        // i 0,00 kWh, a nastepnie "nagle ruszal" - bo zegar symulowany przekraczal
        // granice retencji. Trwale gubilo to ~11 h pierwszej doby (ok. 1,5% danych).
        //
        // Rozwiazanie: zaokraglamy w GORE (polnoc + 1 doba) zamiast w dol. Okno symulacji
        // to wtedy [testStart - 30d + margines, testStart + margines], gdzie
        // margines = 24 h - pora dnia startu testu, czyli zawsze > 0. Koniec okna wypada
        // do 24 h w przyszlosci - to jest bezpieczne, bo zapytania Flux maja stop: 365d,
        // a ceny RDN do rozdzialu wynikow i tak pochodza z osobnych okien historycznych.
        final long simTimeStartRaw = testStartMs - (long) totalMinutes * 60_000L;
        final long simTimeStart = Instant.ofEpochMilli(simTimeStartRaw)
                .atZone(ZoneId.of("Europe/Warsaw"))
                .truncatedTo(ChronoUnit.DAYS)
                .plusDays(1)
                .toInstant()
                .toEpochMilli();

        long marginMinutes = (simTimeStart - simTimeStartRaw) / 60_000L;
        log.info("Test {}: okno symulowane {} -> {} (margines nad granica retencji: {} h {} min)",
                testId,
                Instant.ofEpochMilli(simTimeStart).atZone(ZoneId.of("Europe/Warsaw")),
                Instant.ofEpochMilli(simTimeStart + (long) totalMinutes * 60_000L)
                        .atZone(ZoneId.of("Europe/Warsaw")),
                marginMinutes / 60, marginMinutes % 60);
        if (marginMinutes < 60) {
            log.warn("Test {}: margines nad granica retencji InfluxDB to tylko {} min. "
                    + "Przy 30-dniowej retencji czesc pomiarow pierwszej doby moze zostac "
                    + "odrzucona (HTTP 400 'outside of the retention period').", testId, marginMinutes);
        }

        // SSE: postep wysylamy co 25% ukonczenia zeby nie zalac klienta setkami eventow.
        // progressCheckpoints = [totalMinutes*0.25, 0.50, 0.75, 1.0]
        int nextProgressCheckpoint = 0;
        final int[] progressPercents = {25, 50, 75, 100};
        final int[] progressMinuteThresholds = new int[]{
                (int) (totalMinutes * 0.25),
                (int) (totalMinutes * 0.50),
                (int) (totalMinutes * 0.75),
                totalMinutes - 1
        };

        for (int minute = 0; minute < totalMinutes; minute++) {
            int minuteOfDay = minute % 1440;

            // Emit pomiar tylko co N minut symulowanych (zmniejsza obciazenie InfluxDB)
            if (minute % emitEveryN == 0) {
                // Timestamp SYMULOWANY (nie real time) - rozciagniety na 30 dni wstecz od startu.
                // Kazda symulowana minuta = jedna prawdziwa minuta w timestampie InfluxDB,
                // dzieki czemu agregacja hourly zwraca pelny profil dobowy 720 punktow (30d x 24h).
                long simTimeMs = simTimeStart + (long) minute * 60_000L;
                for (DeviceSimulator sim : simulators) {
                    double power = sim.updatePower(minuteOfDay);
                    mqttPublisher.publishPower(testId, sim.getDeviceId(), power, simTimeMs);
                }
            } else {
                // W minutach bez emisji nadal aktualizujemy stan symulatorow (zeby liczyly cykle)
                for (DeviceSimulator sim : simulators) {
                    sim.updatePower(minuteOfDay);
                }
            }

            // SSE progress event - wysylamy raz na 25% postepu (25/50/75/100)
            if (nextProgressCheckpoint < progressPercents.length
                    && minute >= progressMinuteThresholds[nextProgressCheckpoint]) {
                int pct = progressPercents[nextProgressCheckpoint];
                int simDay = (minute / 1440) + 1;
                int simHour = (minute % 1440) / 60;
                streamEventPublisher.publishProgress(testId, pct,
                        String.format("Dzien %d/%d, godz %02d:00", simDay, durationDays, simHour));
                nextProgressCheckpoint++;
            }

            Thread.sleep(stepMs);

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Test przerwany");
            }
        }
    }
}
