package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import pl.smarthome.platform.api.dto.CreateTestResponse;
import pl.smarthome.platform.api.dto.MeasurementsResponse;
import pl.smarthome.platform.api.dto.TestConfig;
import pl.smarthome.platform.api.dto.TestResponse;
import pl.smarthome.platform.api.dto.TestSummary;
import pl.smarthome.platform.influx.InfluxQueryService;
import pl.smarthome.platform.service.TestService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
@Tag(name = "Tests", description = "Zarządzanie testami symulacyjnymi")
public class TestController {

    private final TestService testService;
    private final InfluxQueryService influxQueryService;

    @PostMapping
    @Operation(summary = "Utwórz i zleć nowy test")
    public ResponseEntity<CreateTestResponse> createTest(@Valid @RequestBody TestConfig config) {
        CreateTestResponse response = testService.createTest(config);
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
}
