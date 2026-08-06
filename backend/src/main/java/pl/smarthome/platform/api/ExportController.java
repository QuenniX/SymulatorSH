package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.smarthome.platform.service.ExportService;

import java.util.UUID;

/**
 * Eksport wynikow testow do pliku CSV lub XLSX - do dalszej analizy Pythonem
 * albo Excelem. Kluczowy endpoint dla czesci badawczej pracy magisterskiej.
 */
@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Eksport", description = "Pobieranie wynikow testu w formacie CSV lub XLSX")
public class ExportController {

    private final ExportService exportService;

    @GetMapping("/{testId}/export")
    @Operation(
            summary = "Eksport wynikow testu do pliku (CSV lub XLSX)",
            description = """
                Zwraca plik z godzinowymi danymi zuzycia energii + wyliczonymi kosztami
                dla 3 taryf (G11, G12, RDN) + cena hurtowa RDN z PSE.

                Format CSV:
                - Separator srednik (';') - Excel PL rozumie od razu
                - Nagłowek: data;godzina;zuzycie_kWh;koszt_G11_zl;koszt_G12_zl;koszt_RDN_zl;cena_RDN_zl_kWh
                - Kropka dziesietna zamieniona na przecinek (locale PL)

                Format XLSX:
                - Arkusz 'Godziny' - dane godzinowe
                - Arkusz 'Podsumowanie' - sumy per taryfa + oszczedność RDN vs G11 [%]
                - Arkusz 'Konfiguracja' - metadata testu
                """
    )
    public ResponseEntity<byte[]> exportTest(
            @PathVariable("testId") UUID testId,
            @Parameter(description = "Format pliku: csv albo xlsx (default: csv)")
            @RequestParam(value = "format", defaultValue = "csv") String format
    ) {
        String fmt = format.toLowerCase().trim();
        byte[] content;
        String contentType;
        String extension;

        switch (fmt) {
            case "xlsx" -> {
                content = exportService.exportXlsx(testId);
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                extension = "xlsx";
            }
            case "csv" -> {
                content = exportService.exportCsv(testId);
                contentType = "text/csv; charset=UTF-8";
                extension = "csv";
            }
            default -> {
                return ResponseEntity.badRequest()
                        .body(("Nieprawidlowy format '" + format + "'. Uzyj csv albo xlsx.").getBytes());
            }
        }

        String filename = "test-" + testId + "." + extension;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }
}
