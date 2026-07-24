package pl.smarthome.platform.service;

import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.RoomDto;

import java.util.List;

/**
 * Statyczna paleta pomieszczeń mieszkania referencyjnego.
 *
 * Na tym etapie hardkodowane 5 pokojów odpowiadających typowemu
 * mieszkaniu 60 m² (kuchnia, salon, sypialnia, łazienka, przedpokój).
 * W przyszłości będzie można wybierać między różnymi layoutami mieszkań.
 */
@Service
public class RoomService {

    public List<RoomDto> listRooms() {
        return List.of(
                RoomDto.builder()
                        .type("KITCHEN")
                        .label("Kuchnia")
                        .build(),
                RoomDto.builder()
                        .type("LIVING_ROOM")
                        .label("Salon")
                        .build(),
                RoomDto.builder()
                        .type("BEDROOM")
                        .label("Sypialnia")
                        .build(),
                RoomDto.builder()
                        .type("BATHROOM")
                        .label("Łazienka")
                        .build(),
                RoomDto.builder()
                        .type("HALLWAY")
                        .label("Przedpokój")
                        .build()
        );
    }
}
