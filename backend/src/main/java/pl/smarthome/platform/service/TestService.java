package pl.smarthome.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.smarthome.platform.api.dto.CreateTestResponse;
import pl.smarthome.platform.api.dto.TestConfig;
import pl.smarthome.platform.api.dto.TestResponse;
import pl.smarthome.platform.api.dto.TestSummary;
import pl.smarthome.platform.domain.TestEntity;
import pl.smarthome.platform.domain.TestStatus;
import pl.smarthome.platform.executor.TestExecutor;
import pl.smarthome.platform.influx.InfluxQueryService;
import pl.smarthome.platform.repository.TestRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestService {

    private final TestRepository testRepository;
    private final TestExecutor testExecutor;
    private final InfluxQueryService influxQueryService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreateTestResponse createTest(@Valid TestConfig config) {
        try {
            UUID id = UUID.randomUUID();
            String json = objectMapper.writeValueAsString(config);

            TestEntity entity = new TestEntity();
            entity.setId(id);
            entity.setName(config.getName());
            entity.setDescription(config.getDescription());
            entity.setConfigJson(json);
            entity.setStatus(TestStatus.QUEUED);
            entity.setDurationDays(config.getDurationDays());
            entity.setSpeedFactor(config.getSpeedFactor());
            entity.setOwnerId("default");
            entity.setCreatedAt(Instant.now());

            testRepository.save(entity);
            log.info("Utworzono test {} - '{}' ({} dni, x{})",
                    id, config.getName(), config.getDurationDays(), config.getSpeedFactor());

            // Submit do executora DOPIERO PO COMMICIE transakcji
            // - inaczej runner odczyta z bazy null (encja nie jest jeszcze persisted)
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    testExecutor.submit(id);
                }
            });

            return CreateTestResponse.builder()
                    .testId(id)
                    .status(TestStatus.QUEUED)
                    .createdAt(entity.getCreatedAt())
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Nie udało się utworzyć testu: " + e.getMessage(), e);
        }
    }

    public List<TestSummary> listTests() {
        return testRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    public TestResponse getTest(UUID id) {
        TestEntity entity = testRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Test nie znaleziony: " + id));

        Long realDuration = null;
        if (entity.getStartedAt() != null && entity.getFinishedAt() != null) {
            realDuration = Duration.between(entity.getStartedAt(), entity.getFinishedAt()).getSeconds();
        }

        try {
            return TestResponse.builder()
                    .testId(entity.getId())
                    .name(entity.getName())
                    .description(entity.getDescription())
                    .status(entity.getStatus())
                    .config(objectMapper.readTree(entity.getConfigJson()))
                    .durationDays(entity.getDurationDays())
                    .speedFactor(entity.getSpeedFactor())
                    .createdAt(entity.getCreatedAt())
                    .startedAt(entity.getStartedAt())
                    .finishedAt(entity.getFinishedAt())
                    .realDurationSeconds(realDuration)
                    .errorMessage(entity.getErrorMessage())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Błąd parsowania konfiguracji testu " + id, e);
        }
    }

    @Transactional
    public void deleteTest(UUID id) {
        TestEntity entity = testRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Test nie znaleziony: " + id));

        if (entity.getStatus() == TestStatus.RUNNING || entity.getStatus() == TestStatus.QUEUED) {
            testExecutor.cancel(id);
            entity.setStatus(TestStatus.CANCELLED);
            entity.setFinishedAt(Instant.now());
            testRepository.save(entity);
            log.info("Test {} anulowany", id);
        } else {
            testRepository.delete(entity);
            log.info("Test {} usunięty", id);
        }
    }

    private TestSummary toSummary(TestEntity entity) {
        return TestSummary.builder()
                .testId(entity.getId())
                .name(entity.getName())
                .status(entity.getStatus())
                .durationDays(entity.getDurationDays())
                .speedFactor(entity.getSpeedFactor())
                .createdAt(entity.getCreatedAt())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .build();
    }
}
