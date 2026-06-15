package pl.smarthome.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceTypeDto {
    private String type;
    private String label;
    private Map<String, Object> defaultParams;
}
