package pl.smarthome.platform.service;

import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.DeviceTypeDto;

import java.util.List;
import java.util.Map;

/**
 * Statyczna paleta dostępnych typów urządzeń.
 *
 * W kolejnych iteracjach przeniesiemy ją do bazy lub do pliku konfiguracyjnego,
 * żeby można było dodawać typy bez przebudowy aplikacji.
 */
@Service
public class DeviceTypeService {

    public List<DeviceTypeDto> listDeviceTypes() {
        return List.of(
                DeviceTypeDto.builder()
                        .type("LIGHT")
                        .label("Oświetlenie")
                        .defaultParams(Map.of("power_w", 60))
                        .build(),
                DeviceTypeDto.builder()
                        .type("REFRIGERATOR")
                        .label("Lodówka")
                        .defaultParams(Map.of("power_w", 150, "duty_cycle", 0.4))
                        .build(),
                DeviceTypeDto.builder()
                        .type("WASHER")
                        .label("Pralka")
                        .defaultParams(Map.of("power_w", 2000, "cycle_minutes", 60))
                        .build(),
                DeviceTypeDto.builder()
                        .type("HEATER")
                        .label("Grzejnik elektryczny")
                        .defaultParams(Map.of("power_w", 1500))
                        .build()
        );
    }
}
