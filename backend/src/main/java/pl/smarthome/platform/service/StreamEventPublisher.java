package pl.smarthome.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.smarthome.platform.api.dto.StreamEvent;
import pl.smarthome.platform.domain.TestEntity;
import pl.smarthome.platform.domain.TestStatus;
import pl.smarthome.platform.repository.TestRepository;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralny broker Server-Sent Events dla platformy.
 *
 * <p>Dwa niezalezne kanaly subskrypcji:</p>
 * <ul>
 *   <li><b>Test streams</b> - {@code Map&lt;testId, List&lt;SseEmitter&gt;&gt;} - eventy per pojedynczy test</li>
 *   <li><b>Batch streams</b> - {@code Map&lt;batchId, List&lt;SseEmitter&gt;&gt;} - eventy agregacyjne per partia</li>
 * </ul>
 *
 * <p>Producent (TestRunner, TestExecutor) wywoluje metody {@link #publishTestEvent}
 * i {@link #publishBatchStatusChange} zeby broadcastowac do wszystkich subskrybentow.</p>
 *
 * <p><b>Keep-alive:</b> co 30s wysylamy pusty ping do wszystkich otwartych emiterow,
 * zeby proxy/NAT nie ubily polaczenia po timeoucie.</p>
 *
 * <p><b>Cleanup:</b> jak emiter rzuca IOException (klient sie rozlaczyl) - usuwamy z listy.
 * SseEmitter timeout 30min - dla dlugich testow.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreamEventPublisher {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;  // 30 min

    // ConcurrentHashMap + CopyOnWriteArrayList - thread-safe, wielu producentow i wielu subskrybentow
    private final Map<UUID, List<SseEmitter>> testEmitters = new ConcurrentHashMap<>();
    private final Map<UUID, List<SseEmitter>> batchEmitters = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final TestRepository testRepository;

    // ==========================================================
    //  Subskrypcja - tworzy nowy SseEmitter i dodaje do listy
    // ==========================================================

    /**
     * Nowa subskrypcja na eventy pojedynczego testu.
     * Wywolane z {@code GET /api/v1/tests/&#123;id&#125;/stream}.
     */
    public SseEmitter subscribeTest(UUID testId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        registerEmitter(testEmitters, testId, emitter);

        // Od razu wyslij aktualny stan (zeby klient wiedzial od czego zaczyna)
        testRepository.findById(testId).ifPresent(entity -> {
            StreamEvent event = StreamEvent.statusChange(
                    testId,
                    entity.getStatus(),
                    "Aktualny stan testu"
            );
            trySend(emitter, event);
        });

        return emitter;
    }

    /**
     * Nowa subskrypcja na eventy agregacyjne partii.
     * Wywolane z {@code GET /api/v1/batches/&#123;batchId&#125;/stream}.
     */
    public SseEmitter subscribeBatch(UUID batchId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        registerEmitter(batchEmitters, batchId, emitter);

        // Wyslij od razu snapshot startowy
        publishBatchSnapshot(batchId, emitter);

        return emitter;
    }

    // ==========================================================
    //  Publikacja - wolane z TestRunner/TestExecutor
    // ==========================================================

    /**
     * Emituje event zmiany statusu pojedynczego testu do wszystkich subskrybentow tego testu.
     * Dodatkowo triggeruje batch snapshot jesli test nalezy do partii.
     */
    public void publishTestEvent(UUID testId, StreamEvent event) {
        broadcastToTest(testId, event);

        // Znajdz batchId i triggeruj snapshot partii
        testRepository.findById(testId).ifPresent(entity -> {
            if (entity.getBatchId() != null) {
                publishBatchSnapshotToAll(entity.getBatchId());
            }
        });
    }

    /**
     * Broadcast statusa i wiadomosci do subskrybentow testu (bez triggera batch).
     * Dla przypadkow gdzie tylko test-stream ma sie updateowac.
     */
    public void publishStatusChange(UUID testId, TestStatus status, String message) {
        publishTestEvent(testId, StreamEvent.statusChange(testId, status, message));
    }

    /**
     * Broadcast eventu postepu (0-100%) dla running testu.
     */
    public void publishProgress(UUID testId, int progressPct, String message) {
        broadcastToTest(testId, StreamEvent.progress(testId, progressPct, message));
        // Progress nie wymaga updatu batch (batch pokazuje tylko statusy, nie procenty)
    }

    /**
     * Broadcast snapshot partii do wszystkich subskrybentow partii.
     * Wywolywane automatycznie gdy zmienia sie status ktoregos z testow w partii.
     */
    public void publishBatchSnapshotToAll(UUID batchId) {
        List<SseEmitter> emitters = batchEmitters.get(batchId);
        if (emitters == null || emitters.isEmpty()) return;

        StreamEvent event = buildBatchSnapshotEvent(batchId);
        if (event == null) return;

        for (SseEmitter emitter : emitters) {
            trySend(emitter, event);
        }
    }

    // ==========================================================
    //  Keep-alive - co 30s ping do wszystkich otwartych emiterow
    // ==========================================================

    /** Co 30 sekund wyslij pusty komentarz zeby proxy nie zamknelo polaczenia. */
    @Scheduled(fixedRate = 30_000)
    public void keepAlive() {
        int pinged = 0;
        for (List<SseEmitter> emitters : testEmitters.values()) {
            for (SseEmitter e : emitters) {
                try {
                    e.send(SseEmitter.event().comment("keep-alive"));
                    pinged++;
                } catch (Exception ignored) { /* dead emiter, cleanup zajmie sie tym przy nastepnym publish */ }
            }
        }
        for (List<SseEmitter> emitters : batchEmitters.values()) {
            for (SseEmitter e : emitters) {
                try {
                    e.send(SseEmitter.event().comment("keep-alive"));
                    pinged++;
                } catch (Exception ignored) { }
            }
        }
        if (pinged > 0) {
            log.debug("SSE keep-alive: pinged {} emitterow", pinged);
        }
    }

    // ==========================================================
    //  Prywatne
    // ==========================================================

    private void registerEmitter(Map<UUID, List<SseEmitter>> pool, UUID key, SseEmitter emitter) {
        pool.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // Cleanup przy disconnect, timeout, complete
        emitter.onCompletion(() -> removeEmitter(pool, key, emitter));
        emitter.onTimeout(() -> removeEmitter(pool, key, emitter));
        emitter.onError(err -> removeEmitter(pool, key, emitter));

        log.debug("SSE subscribe: {} = {} emitterow", key, pool.get(key).size());
    }

    private void removeEmitter(Map<UUID, List<SseEmitter>> pool, UUID key, SseEmitter emitter) {
        List<SseEmitter> list = pool.get(key);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) pool.remove(key);
        }
    }

    private void broadcastToTest(UUID testId, StreamEvent event) {
        List<SseEmitter> emitters = testEmitters.get(testId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            trySend(emitter, event);
        }
    }

    /** Buduje event snapshot dla partii bezposrednio z bazy. */
    private StreamEvent buildBatchSnapshotEvent(UUID batchId) {
        List<TestEntity> tests = testRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (tests.isEmpty()) return null;

        int queued = 0, running = 0, completed = 0, failed = 0, cancelled = 0;
        List<Map<String, Object>> currentlyRunning = new java.util.ArrayList<>();

        for (TestEntity t : tests) {
            switch (t.getStatus()) {
                case QUEUED -> queued++;
                case RUNNING -> {
                    running++;
                    Map<String, Object> info = new HashMap<>();
                    info.put("testId", t.getId());
                    info.put("name", t.getName());
                    currentlyRunning.add(info);
                }
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
            }
        }

        return StreamEvent.builder()
                .batchId(batchId)
                .total(tests.size())
                .queued(queued)
                .running(running)
                .completed(completed)
                .failed(failed)
                .cancelled(cancelled)
                .currentlyRunning(currentlyRunning)
                .timestamp(java.time.Instant.now())
                .build();
    }

    private void publishBatchSnapshot(UUID batchId, SseEmitter emitter) {
        StreamEvent event = buildBatchSnapshotEvent(batchId);
        if (event != null) trySend(emitter, event);
    }

    /** Wysyla event do emitera, jesli sie wywali - ignoruje (cleanup w onError callback). */
    private void trySend(SseEmitter emitter, StreamEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed (klient rozlaczony?): {}", e.getMessage());
            // Nie robimy remove - onError callback juz to zrobi
        } catch (Exception e) {
            log.warn("SSE send unexpected error", e);
        }
    }

    // Metody diagnostyczne - do endpointu /api/v1/streams/stats jesli kiedys bedzie potrzebne
    public int getActiveTestStreams() {
        return testEmitters.values().stream().mapToInt(List::size).sum();
    }

    public int getActiveBatchStreams() {
        return batchEmitters.values().stream().mapToInt(List::size).sum();
    }

    public Set<UUID> getSubscribedTestIds() {
        return testEmitters.keySet();
    }

    public Set<UUID> getSubscribedBatchIds() {
        return batchEmitters.keySet();
    }
}
