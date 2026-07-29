package pl.smarthome.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Zadanie masowego utworzenia testow z listy szablonow.
 *
 * <p>Dla kazdego templateId endpoint tworzy test korzystajac z jego configu,
 * ale nadpisuje wybrane parametry (durationDays, speedFactor, emitEveryNMinutes)
 * wartosciami z tego requestu.</p>
 *
 * <p>Uzytkownik moze wybrac np. 6 archetypow letnich, jeden przycisk = 6 testow
 * z tymi samymi parametrami czasowymi.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchCreateRequest {

    /** Lista szablonow do uruchomienia (wymagane, min 1). */
    @NotEmpty
    private List<UUID> templateIds;

    /** Ile dni symulacji (nadpisuje wartosc z szablonu). */
    @NotNull
    @Min(1)
    @Max(30)
    private Integer durationDays;

    /** Speed factor (nadpisuje wartosc z szablonu). */
    @NotNull
    @Min(1)
    @Max(2160)
    private Integer speedFactor;

    /** Co ile minut sym emitowac pomiary (default 5). Opcjonalne. */
    @Min(1)
    @Max(60)
    private Integer emitEveryNMinutes;

    /** Opcjonalny prefix nazwy testu (np. "[BATCH 2026-07-26]"). */
    private String namePrefix;
}
