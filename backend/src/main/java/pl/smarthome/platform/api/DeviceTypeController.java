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
@Tag(name = "Device Types", description = "Paleta dostępnych typów urządzeń")
public class DeviceTypeController {

    private final DeviceTypeService deviceTypeService;

    @GetMapping
    @Operation(summary = "Lista typów urządzeń z domyślnymi parametrami")
    public List<DeviceTypeDto> listDeviceTypes() {
        return deviceTypeService.listDeviceTypes();
    }
}
