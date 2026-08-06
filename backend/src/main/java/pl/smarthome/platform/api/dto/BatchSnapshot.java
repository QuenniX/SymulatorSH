package pl.smarthome.platform.api.dto;

import lombok.Builder;
import pl.smarthome.platform.domain.TestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot stanu partii testow - agregat statusow + lista testow z ich metadata.
 * Zwracany przez {@code GET /api/v1/batches/{batchId}}.
 *
 * <p>Dla real-time updates uzyj Faza 5: {@code GET /api/v1/batches/{batchId}/stream} (SSE).</p>
 */
@Builder
public record BatchSnapshot(
        /** UUID partii - identyfikuje grupe testow uruchomionych razem. */
        UUID batchId,
        /** Ile testow lacznie w partii. */
        int total,
        /** Ile testow czeka w kolejce. */
        int queued,
        /** Ile testow aktualnie sie wykonuje. */
        int running,
        /** Ile testow zakonczylo sie sukcesem. */
        int completed,
        /** Ile testow zakonczylo sie bledem. */
        int failed,
        /** Ile testow zostalo anulowanych. */
        int cancelled,
        /** Kiedy partia zostala utworzona (najstarszy test w partii). */
        Instant createdAt,
        /** Kiedy wygenerowano ten snapshot. */
        Instant snapshotAt,
        /** Lista wszystkich testow w partii z podstawowymi metadanymi. */
        List<TestInBatch> tests
) {

    /**
     * Pojedynczy test w partii - podstawowe metadane bez pelnej konfiguracji.
     * Dla szczegolow uzyj {@code GET /api/v1/tests/{testId}}.
     */
    @Builder
    public record TestInBatch(
            UUID testId,
            String name,
            TestStatus status,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt
    ) { }
}
