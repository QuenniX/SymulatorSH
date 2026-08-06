package pl.smarthome.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import pl.smarthome.platform.domain.TestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event wysylany w Server-Sent Events streamie ({@code GET /api/v1/tests/&#123;id&#125;/stream}
 * i {@code GET /api/v1/batches/&#123;id&#125;/stream}).
 *
 * <p>Standardowy format zgodny ze SPECYFIKACJA_API.md - jeden schemat dla wszystkich
 * typow eventow. Pola nullable pomijane w JSON dla oszczednosci bajtow w streamie.</p>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEvent(
        /** UUID testu (dla eventow z pojedynczego testu). Null dla batch events. */
        UUID testId,
        /** UUID partii (dla eventow z batch stream). Null dla single-test events. */
        UUID batchId,
        /** Status: QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED */
        TestStatus status,
        /** Postep 0-100 (tylko dla RUNNING). */
        Integer progress,
        /** Wiadomosc dla czlowieka: "Dzien 15 z 30" itp. */
        String message,
        /** Timestamp eventu (UTC ISO). */
        Instant timestamp,

        // ---- Pola dla eventow BATCH (agregat statusow) ----
        Integer total,
        Integer queued,
        Integer running,
        Integer completed,
        Integer failed,
        Integer cancelled,
        /** Lista testow aktualnie running (dla batch stream). */
        List<Map<String, Object>> currentlyRunning,

        // ---- Dodatkowe metadane techniczne ----
        /** Extra info techniczne - simulatedMinute, elapsedRealSec, itp. */
        Map<String, Object> data
) {

    /** Factory - typowy event zmiany statusu pojedynczego testu. */
    public static StreamEvent statusChange(UUID testId, TestStatus status, String message) {
        return StreamEvent.builder()
                .testId(testId)
                .status(status)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    /** Factory - event postepu (0-100%) dla running testu. */
    public static StreamEvent progress(UUID testId, int progressPct, String message) {
        return StreamEvent.builder()
                .testId(testId)
                .status(TestStatus.RUNNING)
                .progress(progressPct)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
