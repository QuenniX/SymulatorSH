package pl.smarthome.platform.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.smarthome.platform.api.dto.DeviceConfig;
import pl.smarthome.platform.api.dto.TestConfig;
import pl.smarthome.platform.domain.TestEntity;
import pl.smarthome.platform.domain.TestStatus;
import pl.smarthome.platform.executor.simulator.DeviceSimulator;
import pl.smarthome.platform.executor.simulator.SimulatorFactory;
import pl.smarthome.platform.mqtt.MqttPublisher;
import pl.smarthome.platform.repository.TestRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pojedynczy bieg testu.
 *
 * Wczytuje konfigurację z bazy, tworzy symulatory dla każdego urządzenia,
 * a następnie w pętli (po jednym kroku symulowanej minuty) odpytuje każdy
 * symulator o bieżącą moc i publikuje na MQTT.
 *
 * Krok rzeczywisty = (60_000 ms / speed_factor). Przy speed_factor=720
 * jedna minuta symulowana zajmuje ~83 ms, a dzień - ~2 minuty.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestRunner {

    private final TestRepository testRepository;
    private final SimulatorFactory simulatorFactory;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void run(UUID testId) {
        TestEntity entity = testRepository.findById(testId).orElse(null);
        if (entity == null) {
            log.error("TestRunner: brak rekordu dla testu {}", testId);
            return;
        }

        try {
            entity.setStatus(TestStatus.RUNNING);
            entity.setStartedAt(Instant.now());
            testRepository.save(entity);

            TestConfig config = objectMapper.readValue(entity.getConfigJson(), TestConfig.class);
            log.info("Test {} '{}' startuje: {} dni, speed_factor={}, urządzeń={}",
                    testId, config.getName(), config.getDurationDays(),
                    config.getSpeedFactor(), config.getDevices().size());

            executeSimulation(testId, config);

            entity.setStatus(TestStatus.COMPLETED);
            entity.setFinishedAt(Instant.now());
            testRepository.save(entity);
            log.info("Test {} ZAKOŃCZONY pomyślnie", testId);

        } catch (Exception e) {
            log.error("Test {} zakończony błędem", testId, e);
            entity.setStatus(TestStatus.FAILED);
            entity.setFinishedAt(Instant.now());
            entity.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            testRepository.save(entity);
        }
    }

    private void executeSimulation(UUID testId, TestConfig config) throws InterruptedException {
        int speedFactor = config.getSpeedFactor();
        int durationDays = config.getDurationDays();
        int totalMinutes = durationDays * 1440;

        // Krok rzeczywisty na jedną minutę symulowaną
        long stepMs = Math.max(1, 60_000L / speedFactor);

        int globalJitterTime = config.getJitter() != null
                ? config.getJitter().getGlobalTimeMinutes() : 0;
        int globalJitterPower = config.getJitter() != null
                ? config.getJitter().getGlobalPowerPercent() : 0;

        long seed = testId.getMostSignificantBits() ^ testId.getLeastSignificantBits();
        List<DeviceSimulator> simulators = new ArrayList<>();
        for (DeviceConfig dc : config.getDevices()) {
            simulators.add(simulatorFactory.create(dc, seed + dc.getId().hashCode(),
                    globalJitterTime, globalJitterPower));
        }

        for (int minute = 0; minute < totalMinutes; minute++) {
            int minuteOfDay = minute % 1440;

            // Znacznik czasu pomiaru = rzeczywisty czas zapisu.
            // Numer minuty symulowanej zachowujemy w payload-zie - dzięki temu
            // można odtworzyć timeline w obrębie doby symulowanej z metadanych.
            long realTimeMs = System.currentTimeMillis();

            for (DeviceSimulator sim : simulators) {
                double power = sim.updatePower(minuteOfDay);
                mqttPublisher.publishPower(testId, sim.getDeviceId(), power, realTimeMs);
            }

            Thread.sleep(stepMs);

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Test przerwany");
            }
        }
    }
}
