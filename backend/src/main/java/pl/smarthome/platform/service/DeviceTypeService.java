package pl.smarthome.platform.service;

import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.DeviceTypeDto;

import java.util.List;
import java.util.Map;

/**Statyczna paleta dostępnych typów urządzeń.*/
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
                        .build(),
                DeviceTypeDto.builder()
                        .type("TV")
                        .label("Telewizor")
                        .defaultParams(Map.of("power_w", 120))
                        .build(),
                DeviceTypeDto.builder()
                        .type("AC")
                        .label("Klimatyzacja")
                        .defaultParams(Map.of(
                                "power_w", 1000,
                                "duty_cycle", 0.5,
                                "cycle_length_minutes", 30))
                        .build(),
                DeviceTypeDto.builder()
                        .type("BOILER")
                        .label("Podgrzewacz wody (bojler)")
                        .defaultParams(Map.of(
                                "power_w", 2000,
                                "duty_cycle", 0.17,
                                "cycle_length_minutes", 60))
                        .build(),
                DeviceTypeDto.builder()
                        .type("OVEN")
                        .label("Piekarnik")
                        .defaultParams(Map.of(
                                "power_w", 2500,
                                "cycle_minutes", 60,
                                "heat_on_minutes", 5,
                                "heat_off_minutes", 3))
                        .build(),
                DeviceTypeDto.builder()
                        .type("DISHWASHER")
                        .label("Zmywarka")
                        .defaultParams(Map.of(
                                "heat_power_w", 1800,
                                "wash_power_w", 200,
                                "dry_power_w", 1500,
                                "heat_phase_minutes", 10,
                                "wash_phase_minutes", 60,
                                "dry_phase_minutes", 20))
                        .build(),
                DeviceTypeDto.builder()
                        .type("KETTLE")
                        .label("Czajnik elektryczny")
                        .defaultParams(Map.of("power_w", 2000, "cycle_minutes", 3))
                        .build(),
                DeviceTypeDto.builder()
                        .type("COMPUTER")
                        .label("Komputer stacjonarny")
                        .defaultParams(Map.of(
                                "idle_power_w", 100,
                                "burst_power_w", 300,
                                "burst_length_minutes", 5,
                                "burst_interval_minutes", 20))
                        .build(),
                DeviceTypeDto.builder()
                        .type("ROUTER")
                        .label("Router / baseload 24/7")
                        .defaultParams(Map.of("power_w", 15))
                        .build()
        );
    }
}
