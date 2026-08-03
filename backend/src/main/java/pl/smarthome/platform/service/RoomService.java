package pl.smarthome.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.smarthome.platform.api.dto.RoomDto;
import pl.smarthome.platform.domain.CustomRoomEntity;
import pl.smarthome.platform.repository.CustomRoomRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Paleta pomieszczen dostepnych w kreatorze testu.
 *
 * <p>Dwa zrodla:</p>
 * <ul>
 *   <li><b>Systemowe</b> - 5 hardkodowanych (KITCHEN, LIVING_ROOM, BEDROOM,
 *       BATHROOM, HALLWAY). Nie mozna ich usunac ani zmienic.</li>
 *   <li><b>Wlasne</b> - dodawane przez uzytkownika przez UI (np. GARAZ, TARAS).
 *       Zapisane w tabeli <code>custom_rooms</code>.</li>
 * </ul>
 *
 * <p>Kazdy pokoj w response ma pole <code>system: boolean</code> zeby frontend
 * wiedzial czy pokazac przycisk "Usun" (tylko dla non-system).</p>
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private final CustomRoomRepository customRoomRepository;

    /** Domyslne 5 pokoi mieszkania referencyjnego. */
    private static final List<RoomDto> SYSTEM_ROOMS = List.of(
            RoomDto.builder().type("KITCHEN").label("Kuchnia").system(true).build(),
            RoomDto.builder().type("LIVING_ROOM").label("Salon").system(true).build(),
            RoomDto.builder().type("BEDROOM").label("Sypialnia").system(true).build(),
            RoomDto.builder().type("BATHROOM").label("Łazienka").system(true).build(),
            RoomDto.builder().type("HALLWAY").label("Przedpokój").system(true).build()
    );

    /**
     * Zwraca wszystkie dostepne pokoje: 5 systemowych + wszystkie wlasne z bazy.
     * Systemowe pierwsze, potem wlasne posortowane po dacie utworzenia (najstarsze pierwsze).
     */
    public List<RoomDto> listRooms() {
        List<RoomDto> all = new ArrayList<>(SYSTEM_ROOMS);
        customRoomRepository.findAll().stream()
                .sorted(Comparator.comparing(CustomRoomEntity::getCreatedAt))
                .map(e -> RoomDto.builder()
                        .type(e.getType())
                        .label(e.getLabel())
                        .system(false)
                        .build())
                .forEach(all::add);
        return all;
    }

    /**
     * Dodaje wlasny pokoj. Type jest generowany z label (uppercase, bez polskich znakow, bez spacji).
     * Jesli type juz istnieje, dopisuje _2, _3 itd.
     */
    @Transactional
    public RoomDto createCustomRoom(String label) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("Nazwa pomieszczenia nie moze byc pusta");
        }
        String baseType = slugify(label.trim());
        String type = uniquify(baseType);

        CustomRoomEntity entity = CustomRoomEntity.builder()
                .type(type)
                .label(label.trim())
                .createdAt(Instant.now())
                .build();
        customRoomRepository.save(entity);

        return RoomDto.builder().type(type).label(entity.getLabel()).system(false).build();
    }

    /**
     * Usuwa wlasny pokoj. Systemowe (KITCHEN, LIVING_ROOM, itd.) nie da sie usunac.
     */
    @Transactional
    public void deleteCustomRoom(String type) {
        if (isSystemRoom(type)) {
            throw new IllegalArgumentException("Nie mozna usunac systemowego pomieszczenia: " + type);
        }
        if (!customRoomRepository.existsById(type)) {
            throw new IllegalArgumentException("Pomieszczenie nie istnieje: " + type);
        }
        customRoomRepository.deleteById(type);
    }

    private boolean isSystemRoom(String type) {
        return SYSTEM_ROOMS.stream().anyMatch(r -> r.getType().equals(type));
    }

    /**
     * Konwertuje polska nazwe na techniczny identyfikator.
     * Przyklad: "Garaż babci Wandy" -> "GARAZ_BABCI_WANDY"
     */
    private String slugify(String label) {
        String s = label.toUpperCase(Locale.ROOT);
        // Podstawowe polskie znaki -> ASCII
        s = s.replace("Ą", "A").replace("Ć", "C").replace("Ę", "E").replace("Ł", "L")
             .replace("Ń", "N").replace("Ó", "O").replace("Ś", "S").replace("Ź", "Z").replace("Ż", "Z");
        // Wszystko co nie jest litera/cyfra -> podkreslnik
        s = s.replaceAll("[^A-Z0-9]+", "_");
        // Usun podkreslniki z brzegow
        s = s.replaceAll("^_+|_+$", "");
        // Ogranicz dlugosc
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "POKOJ" : s;
    }

    /**
     * Jesli baseType juz istnieje w bazie albo w systemowych, dopisuje _2, _3 itd.
     */
    private String uniquify(String baseType) {
        if (!exists(baseType)) return baseType;
        int i = 2;
        while (exists(baseType + "_" + i)) i++;
        return baseType + "_" + i;
    }

    private boolean exists(String type) {
        return isSystemRoom(type) || customRoomRepository.existsById(type);
    }
}
