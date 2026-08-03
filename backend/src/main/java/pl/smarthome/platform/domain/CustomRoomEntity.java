package pl.smarthome.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Wlasny pokoj dodany przez uzytkownika przez kreator.
 * 5 domyslnych pokoi (KITCHEN, LIVING_ROOM, itd.) pozostaje hardkodowanych
 * w RoomService jako "systemowe" - nie sa zapisane w tej tabeli.
 */
@Entity
@Table(name = "custom_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomRoomEntity {

    /** Techniczny identyfikator UPPER_CASE bez polskich znakow (np. GARAZ, TARAS). */
    @Id
    @Column(name = "type", nullable = false, length = 50)
    private String type;

    /** Nazwa wyswietlana z polskimi znakami (np. "Garaż", "Taras"). */
    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
