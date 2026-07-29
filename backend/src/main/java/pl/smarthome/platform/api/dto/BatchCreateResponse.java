package pl.smarthome.platform.api.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Wynik batch create - lista utworzonych testow + ewentualne bledy per szablon.
 */
@Builder
public record BatchCreateResponse(
        int requestedCount,
        int createdCount,
        int failedCount,
        List<UUID> createdTestIds,
        List<BatchFailure> failures
) {
    @Builder
    public record BatchFailure(
            UUID templateId,
            String templateName,
            String errorMessage
    ) { }
}
