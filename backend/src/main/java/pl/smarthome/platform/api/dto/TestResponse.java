package pl.smarthome.platform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import pl.smarthome.platform.domain.TestStatus;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResponse {
    private UUID testId;
    private String name;
    private String description;
    private TestStatus status;
    private JsonNode config;
    private Integer durationDays;
    private Integer speedFactor;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Long realDurationSeconds;
    private String errorMessage;
}
