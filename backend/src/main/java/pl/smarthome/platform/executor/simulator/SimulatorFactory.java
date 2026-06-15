package pl.smarthome.platform.executor.simulator;

import org.springframework.stereotype.Component;
import pl.smarthome.platform.api.dto.DeviceConfig;

/**
 * Fabryka symulatorów - tworzy odpowiedni typ symulatora
 * na podstawie pola "type" w konfiguracji urządzenia.
 */
@Component
public class SimulatorFactory {

    public DeviceSimulator create(DeviceConfig config,
                                  long randomSeed,
                                  int globalJitterTimeMinutes,
                                  int globalJitterPowerPercent) {
        String type = config.getType() == null ? "" : config.getType().toUpperCase();
        return switch (type) {
            case "LIGHT" -> new LightSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "REFRIGERATOR" -> new RefrigeratorSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "WASHER" -> new WasherSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "HEATER" -> new HeaterSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            default -> throw new IllegalArgumentException("Nieznany typ urządzenia: " + type);
        };
    }
}
