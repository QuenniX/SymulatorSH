package pl.smarthome.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Konfiguracja pojedynczego testu przesyłana w body POST /tests.
 * Zgodna z dokumentem docs/json-schema.md.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TestConfig {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    @Min(1)
    @Max(30)
    private Integer durationDays;

    @NotNull
    @Min(1)
    @Max(1000)
    private Integer speedFactor;

    @Builder.Default
    private Integer retentionDays = 30;

    private Jitter jitter;

    @NotEmpty
    @Valid
    private List<DeviceConfig> devices;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Jitter {
        @Builder.Default
        private Integer globalTimeMinutes = 0;
        @Builder.Default
        private Integer globalPowerPercent = 0;
    }
}
