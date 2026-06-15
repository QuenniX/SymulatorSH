package pl.smarthome.platform.executor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Singleton zarządzający pulą wątków wykonujących testy.
 *
 * Po zleceniu testu przez POST /tests serwis aplikacyjny wywołuje
 * submit(testId). Executor pobiera wątek z puli i odpala TestRunner,
 * który sam wczytuje konfigurację z bazy i mieli symulację.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestExecutor {

    @Value("${platform.executor.pool-size:3}")
    private int poolSize;

    private final TestRunner testRunner;

    private ExecutorService executor;

    private final ConcurrentHashMap<UUID, Future<?>> runningTests = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r);
            t.setName("test-executor-" + t.getId());
            t.setDaemon(false);
            return t;
        });
        log.info("TestExecutor zainicjalizowany z poolSize={}", poolSize);
    }

    public void submit(UUID testId) {
        Future<?> future = executor.submit(() -> {
            try {
                testRunner.run(testId);
            } finally {
                runningTests.remove(testId);
            }
        });
        runningTests.put(testId, future);
        log.info("Test {} przekazany do executora (active={})", testId, runningTests.size());
    }

    public boolean cancel(UUID testId) {
        Future<?> future = runningTests.get(testId);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        if (cancelled) {
            runningTests.remove(testId);
            log.info("Test {} anulowany", testId);
        }
        return cancelled;
    }

    @PreDestroy
    public void shutdown() {
        log.info("TestExecutor: shutdown");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
