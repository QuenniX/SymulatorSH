package pl.smarthome.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Opis pojedynczego pomieszczenia mieszkania referencyjnego.
 * Wykorzystywane przez frontend do zbudowania palety pokojów
 * w konfiguratorze urządzeń i wizualizacji 3D.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomDto {
    /** Identyfikator (np. KITCHEN, LIVING_ROOM, GARAZ). */
    private String type;
    /** Etykieta wyświetlana w UI (po polsku). */
    private String label;
    /**
     * True dla 5 domyslnych pokoi (KITCHEN, LIVING_ROOM, BEDROOM, BATHROOM, HALLWAY).
     * False dla pokoi dodanych przez uzytkownika przez kreator.
     * Frontend uzywa tego do pokazania/ukrycia przycisku "Usun".
     */
    private boolean system;
}
