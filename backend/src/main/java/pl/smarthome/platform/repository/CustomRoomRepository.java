package pl.smarthome.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.smarthome.platform.domain.CustomRoomEntity;

/**
 * Repository dla pokoi dodawanych przez uzytkownika.
 * Klucz podstawowy to pole `type` (String) - nie generujemy UUID
 * bo type i tak musi byc unikalny (pojawia sie w JSON konfiguracji testu).
 */
@Repository
public interface CustomRoomRepository extends JpaRepository<CustomRoomEntity, String> {
}
