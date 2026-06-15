package pl.smarthome.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeasurementPoint {
    private Instant timestamp;
    private String deviceId;
    private Double powerW;
}
