package pl.smarthome.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.smarthome.platform.domain.ApiKeyEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository dla kluczy API.
 * Klucz w bazie jest identyfikowany przez {@code key_hash} (SHA256 hex plain-textu klucza).
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    /** Znajdz klucz po SHA256 hash - uzywane przy walidacji przychodzacego zapytania. */
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);
}
