package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.smarthome.platform.api.dto.DeviceTypeDto;
import pl.smarthome.platform.service.DeviceTypeService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/device-types")
@RequiredArgsConstructor
@Tag(name = "Typy urządzeń", description = "Paleta dostępnych typów urządzeń AGD/RTV z domyślnymi parametrami (12 typów: LIGHT, TV, HEATER, AC, BOILER, OVEN, DISHWASHER, KETTLE, COMPUTER, ROUTER, WASHER, REFRIGERATOR).")
public class DeviceTypeController {

    private final DeviceTypeService deviceTypeService;

    @GetMapping
    @Operation(
            summary = "Lista dostępnych typów urządzeń AGD/RTV z domyślnymi parametrami",
            description = "Zwraca 12 typów urządzeń: LIGHT, TV, HEATER, AC, BOILER, OVEN, DISHWASHER, "
                    + "KETTLE, COMPUTER, ROUTER, WASHER, REFRIGERATOR. Każdy ma domyślną moc i parametry cyklu pracy. "
                    + "Używane w kreatorze jako paleta wyboru."
    )
    public List<DeviceTypeDto> listDeviceTypes() {
        return deviceTypeService.listDeviceTypes();
    }
}
