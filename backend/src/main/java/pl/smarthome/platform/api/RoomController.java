package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.smarthome.platform.api.dto.RoomDto;
import pl.smarthome.platform.service.RoomService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
@Tag(name = "Pomieszczenia", description = "Paleta pomieszczeń mieszkania - 5 systemowych (Kuchnia, Salon, Sypialnia, Łazienka, Przedpokój) + własne dodane przez użytkownika (np. Garaż, Taras).")
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    @Operation(
            summary = "Lista wszystkich pomieszczeń (systemowe + własne)",
            description = "Zwraca 5 pomieszczeń systemowych (KITCHEN, LIVING_ROOM, BEDROOM, BATHROOM, HALLWAY) "
                    + "plus wszystkie własne dodane przez użytkowników. Pole `system: true` oznacza że pomieszczenia "
                    + "nie da się usunąć (systemowe są fundamentem)."
    )
    public List<RoomDto> listRooms() {
        return roomService.listRooms();
    }

    @PostMapping
    @Operation(summary = "Dodaj wlasne pomieszczenie (np. Garaz, Taras)")
    public ResponseEntity<RoomDto> createCustomRoom(@RequestBody CreateRoomRequest request) {
        RoomDto created = roomService.createCustomRoom(request.getLabel());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{type}")
    @Operation(summary = "Usun wlasne pomieszczenie (systemowych nie da sie usunac)")
    public ResponseEntity<Void> deleteCustomRoom(@PathVariable("type") String type) {
        roomService.deleteCustomRoom(type);
        return ResponseEntity.noContent().build();
    }

    /** Request DTO dla tworzenia wlasnego pomieszczenia. */
    @Data
    public static class CreateRoomRequest {
        @NotBlank(message = "Nazwa pomieszczenia jest wymagana")
        @Size(min = 2, max = 100, message = "Nazwa musi miec od 2 do 100 znakow")
        private String label;
    }
}
