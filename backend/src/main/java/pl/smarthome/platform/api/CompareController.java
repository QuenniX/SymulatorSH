package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.smarthome.platform.api.dto.CompareResponse;
import pl.smarthome.platform.service.CompareService;

import java.util.List;
import java.util.UUID;

/**
 * Porownanie wielu testow - jedno wywolanie API, zamiast zapytywac osobno o kazdy test.
 *
 * <p>Idealne do budowania dashboardu / analizy porownawczej gdzie masz 5-100 testow
 * i chcesz zobaczyc ktory najbardziej sie oplaca na RDN, ktory ma najwyzsze ryzyko itd.</p>
 */
@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Porownanie", description = "Porownanie wielu testow jednym wywołaniem")
public class CompareController {

    private final CompareService compareService;

    @PostMapping("/compare")
    @Operation(
            summary = "Porownaj wiele testow (jedno wywolanie)",
            description = """
                Zwraca agregat metryk dla listy testow: koszty w 3 taryfach, oszczednosc RDN vs G11,
                mediana kosztu dziennego, VaR i CVaR 5%, najtansza taryfa. Do 100 testow na raz.

                Wynik posortowany od najkorzystniejszego dla RDN (najwyzsze savings).
                Summary wskazuje best/worst dla RDN i srednia oszczednosc.

                Uzyj gdy chcesz zbudowac dashboard porownawczy albo tabele wynikow dla pracy magisterskiej.
                """
    )
    public ResponseEntity<CompareResponse> compare(@RequestBody CompareRequest request) {
        log.info("Compare: {} testow", request.getTestIds().size());
        return ResponseEntity.ok(compareService.compare(request.getTestIds()));
    }

    @Data
    public static class CompareRequest {
        @NotEmpty(message = "Lista testIds nie moze byc pusta")
        @Size(max = 100, message = "Maksymalnie 100 testow na jedno porownanie")
        private List<UUID> testIds;
    }
}
