package pl.smarthome.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Konfiguracja pojedynczego urządzenia w teście.
 * Pole schedule może być listą obiektów Action lub stringiem ("always_on" / "always_off").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceConfig {

    @NotBlank
    private String id;

    @NotBlank
    private String type;

    /**
     * Pomieszczenie, w którym stoi urządzenie (np. KITCHEN, LIVING_ROOM).
     * Opcjonalne - używane przez wizualizację 3D i konfigurator UI.
     * Lista dostępnych wartości: GET /api/v1/rooms.
     */
    private String room;

    private Map<String, Object> params;

    /** Może być List<Action> albo String ("always_on"/"always_off"). */
    private Object schedule;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {
        private String action;
        private String at;
        private Integer durationMinutes;
        private Integer jitterTimeMinutes;
    }

    /** Helper: czy schedule jest stringiem typu always_on/always_off. */
    @JsonIgnore
    public boolean isScheduleString() {
        return schedule instanceof String;
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getScheduleList() {
        if (schedule instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @JsonIgnore
    public String getScheduleString() {
        return schedule instanceof String s ? s : null;
    }
}
