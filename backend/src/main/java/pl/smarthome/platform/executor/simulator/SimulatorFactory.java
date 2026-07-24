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
            case "TV" -> new TvSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "AC" -> new AcSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "BOILER" -> new BoilerSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "OVEN" -> new OvenSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "DISHWASHER" -> new DishwasherSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "KETTLE" -> new KettleSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "COMPUTER" -> new ComputerSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            case "ROUTER" -> new RouterSimulator(config, randomSeed,
                    globalJitterTimeMinutes, globalJitterPowerPercent);
            default -> throw new IllegalArgumentException("Nieznany typ urządzenia: " + type);
        };
    }
}
