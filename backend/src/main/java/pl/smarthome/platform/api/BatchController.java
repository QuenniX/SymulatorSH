package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.smarthome.platform.api.dto.BatchSnapshot;
import pl.smarthome.platform.service.BatchService;

import java.util.Map;
import java.util.UUID;

/**
 * Endpointy zarzadzania partiami testow.
 *
 * <p>Partia to grupa testow uruchomionych razem przez {@code POST /api/v1/tests/batch}.
 * Kazda partia ma unikalny {@code batchId} (UUID) i testy w niej maja to pole ustawione
 * w bazie ({@code tests.batch_id}).</p>
 *
 * <p>Do samego tworzenia partii uzyj {@code POST /api/v1/tests/batch} w {@link TestController}.</p>
 */
@RestController
@RequestMapping("/api/v1/batches")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Partie testow", description = "Zarzadzanie grupami testow uruchomionymi razem")
public class BatchController {

    private final BatchService batchService;

    @GetMapping("/{batchId}")
    @Operation(
            summary = "Snapshot stanu partii testow",
            description = """
                Zwraca aktualny stan wszystkich testow w partii: ile jest queued, running,
                completed, failed i cancelled. Zawiera tez pelna liste testow z ich statusami.

                Dla real-time updates uzyj GET /api/v1/batches/{batchId}/stream (SSE, Faza 5).
                """
    )
    public ResponseEntity<?> getBatch(@PathVariable("batchId") UUID batchId) {
        try {
            BatchSnapshot snapshot = batchService.snapshot(batchId);
            return ResponseEntity.ok(snapshot);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", Map.of(
                            "code", "BATCH_NOT_FOUND",
                            "message", "Partia nie znaleziona: " + batchId,
                            "statusCode", 404
                    )));
        }
    }

    @DeleteMapping("/{batchId}")
    @Operation(
            summary = "Anuluj wszystkie testy w partii",
            description = """
                Anuluje wszystkie testy w partii ktore sa QUEUED albo RUNNING.
                Testy COMPLETED/FAILED/CANCELLED zostaja bez zmian.
                Zwraca liczbe faktycznie anulowanych testow.
                """
    )
    public ResponseEntity<?> cancelBatch(@PathVariable("batchId") UUID batchId) {
        try {
            int cancelled = batchService.cancelBatch(batchId);
            return ResponseEntity.ok(Map.of(
                    "batchId", batchId,
                    "cancelledCount", cancelled,
                    "message", cancelled == 0
                            ? "Zadne testy nie kwalifikowaly sie do anulowania (juz zakonczone)"
                            : "Anulowano " + cancelled + " testow z partii"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", Map.of(
                            "code", "BATCH_NOT_FOUND",
                            "message", "Partia nie znaleziona: " + batchId,
                            "statusCode", 404
                    )));
        }
    }
}
