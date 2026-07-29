package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.smarthome.platform.api.dto.EnergyPriceDto;
import pl.smarthome.platform.service.EnergyPriceService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/prices")
@RequiredArgsConstructor
@Tag(name = "Energy Prices", description = "Godzinowe ceny energii z RDN (Rynek Dnia Nastepnego)")
public class EnergyPriceController {

    private final EnergyPriceService priceService;

    @GetMapping
    @Operation(summary = "24 ceny godzinowe dla wybranej doby")
    public List<EnergyPriceDto> getByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return priceService.listByDate(date);
    }

    @GetMapping("/range")
    @Operation(summary = "Ceny w zakresie dat (do analiz historycznych)")
    public List<EnergyPriceDto> getRange(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return priceService.listByRange(from, to);
    }

    @PostMapping("/fetch")
    @Operation(summary = "Reczne pobranie cen z PSE dla wybranego dnia (test/backfill jednodniowy)")
    public ResponseEntity<FetchResult> fetchManually(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int saved = priceService.fetchAndSave(date);
        return ResponseEntity.ok(new FetchResult(date, saved));
    }

    @PostMapping("/backfill")
    @Operation(summary = "Masowy import cen z zakresu dat (jednorazowo, do prefetchu historii)")
    public ResponseEntity<EnergyPriceService.BackfillResult> backfill(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(priceService.backfill(from, to));
    }

    public record FetchResult(LocalDate date, int savedRecords) { }
}
