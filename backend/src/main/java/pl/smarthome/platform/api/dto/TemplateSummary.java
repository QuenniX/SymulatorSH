package pl.smarthome.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Krótkie info o szablonie (bez pełnego JSON-a konfiguracji) - do listy. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateSummary {
    private UUID templateId;
    private String name;
    private String description;
    private Instant createdAt;
}
