package pl.smarthome.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import pl.smarthome.platform.api.dto.BatchCreateRequest;
import pl.smarthome.platform.api.dto.BatchCreateResponse;
import pl.smarthome.platform.api.dto.CostBreakdown;
import pl.smarthome.platform.api.dto.CreateTestResponse;
import pl.smarthome.platform.api.dto.MeasurementsResponse;
import pl.smarthome.platform.api.dto.ProjectedCostBreakdown;
import pl.smarthome.platform.api.dto.TemplateResponse;
import pl.smarthome.platform.api.dto.TestConfig;
import pl.smarthome.platform.api.dto.TestResponse;
import pl.smarthome.platform.api.dto.TestSummary;
import pl.smarthome.platform.influx.InfluxQueryService;
import pl.smarthome.platform.service.TemplateService;
import pl.smarthome.platform.service.TestService;
import pl.smarthome.platform.tariff.CostCalculatorService;

import java.time.LocalDate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tests", description = "Zarządzanie testami symulacyjnymi")
public class TestController {

    private final TestService testService;
    private final InfluxQueryService influxQueryService;
    private final CostCalculatorService costCalculatorService;
    private final TemplateService templateService;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Operation(summary = "Utwórz i zleć nowy test")
    public ResponseEntity<CreateTestResponse> createTest(@Valid @RequestBody TestConfig config) {
        CreateTestResponse response = testService.createTest(config);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/batch")
    @Operation(
            summary = "Batch: utworz wiele testow z listy szablonow",
            description = "Dla kazdego templateId wczytuje szablon, nadpisuje durationDays/speedFactor/emitEveryNMinutes "
                    + "i tworzy test. Zwraca liste ID + ewentualne bledy per szablon. "
                    + "Nie transakcyjne - jeden blad nie blokuje reszty."
    )
    public ResponseEntity<BatchCreateResponse> createBatch(@Valid @RequestBody BatchCreateRequest request) {
        log.info("Batch create: {} szablonow, dni={}, speed={}, emitEveryN={}",
                request.getTemplateIds().size(),
                request.getDurationDays(),
                request.getSpeedFactor(),
                request.getEmitEveryNMinutes());

        List<UUID> created = new ArrayList<>();
        List<BatchCreateResponse.BatchFailure> failures = new ArrayList<>();

        for (UUID templateId : request.getTemplateIds()) {
            String templateName = "unknown";
            try {
                TemplateResponse template = templateService.getTemplate(templateId);
                templateName = template.getName();

                // Deserializuj config szablonu do TestConfig
                TestConfig config = objectMapper.convertValue(template.getConfig(), TestConfig.class);

                // Nadpisz parametry
                config.setDurationDays(request.getDurationDays());
                config.setSpeedFactor(request.getSpeedFactor());
                if (request.getEmitEveryNMinutes() != null) {
                    config.setEmitEveryNMinutes(request.getEmitEveryNMinutes());
                }

                // Prefix nazwy
                if (request.getNamePrefix() != null && !request.getNamePrefix().isBlank()) {
                    config.setName(request.getNamePrefix() + " " + config.getName());
                }

                CreateTestResponse resp = testService.createTest(config);
                created.add(resp.getTestId());
                log.info("Batch: utworzono test {} z szablonu {} ('{}')",
                        resp.getTestId(), templateId, templateName);

            } catch (Exception e) {
                log.error("Batch: blad dla szablonu {} ('{}'): {}",
                        templateId, templateName, e.getMessage());
                failures.add(BatchCreateResponse.BatchFailure.builder()
                        .templateId(templateId)
                        .templateName(templateName)
                        .errorMessage(e.getMessage())
                        .build());
            }
        }

        BatchCreateResponse response = BatchCreateResponse.builder()
                .requestedCount(request.getTemplateIds().size())
                .createdCount(created.size())
                .failedCount(failures.size())
                .createdTestIds(created)
                .failures(failures)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Lista wszystkich testów")
    public List<TestSummary> listTests() {
        return testService.listTests();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Szczegóły konkretnego testu")
    public TestResponse getTest(@PathVariable("id") UUID id) {
        return testService.getTest(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Anuluj aktywny test lub usuń zakończony")
    public ResponseEntity<Void> deleteTest(@PathVariable("id") UUID id) {
        testService.deleteTest(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/measurements")
    @Operation(summary = "Pomiary mocy dla testuz InfluxDB")
    public MeasurementsResponse getMeasurements(
            @PathVariable("id") UUID id,
            @RequestParam(value = "device_id", required = false) String deviceFilter) {
        return MeasurementsResponse.builder()
                .testId(id)
                .points(influxQueryService.getMeasurements(id, deviceFilter))
                .build();
    }

    @GetMapping("/{id}/costs")
    @Operation(
            summary = "Koszt testu w 3 taryfach (G11, G12, RDN)",
            description = "Agreguje zuzycie z InfluxDB per godzina i liczy koszt dla kazdej taryfy. "
                    + "Rok testu okresla parametry cenowe (2024/2025/2026)."
    )
    public CostBreakdown getCosts(@PathVariable("id") UUID id) {
        return costCalculatorService.calculateForTest(id);
    }

    @GetMapping("/{id}/costs/projected")
    @Operation(
            summary = "Projekcja historyczna kosztu (bootstrap na okresie cen RDN)",
            description = "Wyciaga 24-godzinny profil z testu i multiplikuje go przez wybrany "
                    + "okres [from..to] uzywajac realnych historycznych cen RDN z bazy. "
                    + "Zwraca sumy w 3 taryfach + statystyki dzienne (min, max, mediana, VaR 5%). "
                    + "Kluczowe dla czesci badawczej pracy magisterskiej."
    )
    public ProjectedCostBreakdown getProjectedCosts(
            @PathVariable("id") UUID id,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return costCalculatorService.calculateProjectedCosts(id, from, to);
    }
}
