package pl.smarthome.platform.api.dto;

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
public class TestSummary {
    private UUID testId;
    private String name;
    private TestStatus status;
    private Integer durationDays;
    private Integer speedFactor;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
}
