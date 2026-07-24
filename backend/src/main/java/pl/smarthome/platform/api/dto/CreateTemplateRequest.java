package pl.smarthome.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Żądanie utworzenia nowego szablonu. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTemplateRequest {

    @NotBlank
    private String name;

    private String description;

    /** Konfiguracja testu do zapisania (dowolny JSON). */
    @NotNull
    private Object config;
}
