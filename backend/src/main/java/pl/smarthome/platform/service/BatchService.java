package pl.smarthome.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.smarthome.platform.api.dto.BatchSnapshot;
import pl.smarthome.platform.domain.TestEntity;
import pl.smarthome.platform.domain.TestStatus;
import pl.smarthome.platform.executor.TestExecutor;
import pl.smarthome.platform.repository.TestRepository;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Zarzadzanie partiami testow uruchomionymi razem przez POST /tests/batch.
 *
 * <p>Kazda partia ma UUID (batchId) generowany w momencie POST /tests/batch.
 * Wszystkie testy tej partii maja to pole ustawione w bazie (kolumna batch_id).</p>
 *
 * <p>Ten serwis oferuje 2 operacje na partii:</p>
 * <ul>
 *   <li>{@link #snapshot(UUID)} - aktualny stan (queued/running/completed/failed + lista testow)</li>
 *   <li>{@link #cancelBatch(UUID)} - anuluj wszystkie testy z partii (poprzez TestExecutor)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchService {

    private final TestRepository testRepository;
    private final TestExecutor testExecutor;

    /**
     * Zwraca snapshot stanu partii - agregat statusow + lista testow z ich progressem.
     *
     * @throws IllegalArgumentException jesli batchId nie istnieje (zadnych testow z tym batchId)
     */
    public BatchSnapshot snapshot(UUID batchId) {
        List<TestEntity> tests = testRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (tests.isEmpty()) {
            throw new IllegalArgumentException("Partia nie znaleziona: " + batchId);
        }

        // Zliczaj statusy
        Map<TestStatus, Integer> counts = new EnumMap<>(TestStatus.class);
        for (TestStatus s : TestStatus.values()) counts.put(s, 0);
        for (TestEntity t : tests) counts.merge(t.getStatus(), 1, Integer::sum);

        // Mapuj testy do wersji zwięzłej
        List<BatchSnapshot.TestInBatch> testsInBatch = tests.stream()
                .map(t -> BatchSnapshot.TestInBatch.builder()
                        .testId(t.getId())
                        .name(t.getName())
                        .status(t.getStatus())
                        .createdAt(t.getCreatedAt())
                        .startedAt(t.getStartedAt())
                        .finishedAt(t.getFinishedAt())
                        .build())
                .toList();

        // Timestamp najstarszego testu = "kiedy partia zostala utworzona"
        Instant batchCreatedAt = tests.get(0).getCreatedAt();

        return BatchSnapshot.builder()
                .batchId(batchId)
                .total(tests.size())
                .queued(counts.get(TestStatus.QUEUED))
                .running(counts.get(TestStatus.RUNNING))
                .completed(counts.get(TestStatus.COMPLETED))
                .failed(counts.get(TestStatus.FAILED))
                .cancelled(counts.get(TestStatus.CANCELLED))
                .createdAt(batchCreatedAt)
                .snapshotAt(Instant.now())
                .tests(testsInBatch)
                .build();
    }

    /**
     * Anuluje wszystkie testy w partii - wywoluje TestExecutor.cancel() na kazdym QUEUED/RUNNING.
     * COMPLETED/FAILED/CANCELLED zostawia w spokoju.
     *
     * @return liczba anulowanych testow
     */
    @Transactional
    public int cancelBatch(UUID batchId) {
        List<TestEntity> tests = testRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (tests.isEmpty()) {
            throw new IllegalArgumentException("Partia nie znaleziona: " + batchId);
        }

        int cancelledCount = 0;
        for (TestEntity t : tests) {
            if (t.getStatus() == TestStatus.QUEUED || t.getStatus() == TestStatus.RUNNING) {
                boolean ok = testExecutor.cancel(t.getId());
                if (ok) cancelledCount++;
            }
        }

        log.info("Anulowano {} testow z partii {} (na {} total)", cancelledCount, batchId, tests.size());
        return cancelledCount;
    }
}
