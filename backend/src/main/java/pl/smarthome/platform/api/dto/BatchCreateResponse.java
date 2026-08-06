package pl.smarthome.platform.api.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Wynik batch create - lista utworzonych testow + ewentualne bledy per szablon
 * + batchId grupujacy wszystkie utworzone testy.
 *
 * <p>Uzyj {@code batchId} zeby:</p>
 * <ul>
 *   <li>{@code GET /api/v1/batches/{batchId}} - snapshot stanu calej partii</li>
 *   <li>{@code GET /api/v1/batches/{batchId}/stream} - real-time updates (SSE, Faza 5)</li>
 *   <li>{@code GET /api/v1/tests?batchId=xxx} - lista testow tej partii</li>
 *   <li>{@code DELETE /api/v1/batches/{batchId}} - anuluj cala partie</li>
 * </ul>
 */
@Builder
public record BatchCreateResponse(
        /** UUID grupujacy wszystkie testy utworzone tym wywołaniem. */
        UUID batchId,
        int requestedCount,
        int createdCount,
        int failedCount,
        List<UUID> createdTestIds,
        List<BatchFailure> failures,
        /** URL do subskrypcji Server-Sent Events dla tej partii (Faza 5 API). */
        String streamUrl
) {
    @Builder
    public record BatchFailure(
            UUID templateId,
            String templateName,
            String errorMessage
    ) { }
}
