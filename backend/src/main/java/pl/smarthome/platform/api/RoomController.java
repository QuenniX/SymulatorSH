package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.smarthome.platform.api.dto.RoomDto;
import pl.smarthome.platform.service.RoomService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
@Tag(name = "Rooms", description = "Paleta pomieszczeń mieszkania referencyjnego")
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    @Operation(summary = "Lista pomieszczeń dostępnych w mieszkaniu referencyjnym")
    public List<RoomDto> listRooms() {
        return roomService.listRooms();
    }
}
