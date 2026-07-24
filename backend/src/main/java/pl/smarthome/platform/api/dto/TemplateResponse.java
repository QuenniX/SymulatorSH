package pl.smarthome.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Pełny szablon zwracany z GET /templates/{id}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateResponse {
    private UUID templateId;
    private String name;
    private String description;
    /** Pełny JSON konfiguracji do wczytania w kreatorze. */
    private Object config;
    private Instant createdAt;
}
