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
import java.util.UUID;

/**
 * Klucz API dla zewnetrznego uzytkownika programatycznego dostepu.
 *
 * <p>Zasady:</p>
 * <ul>
 *   <li>Klucz plain text zwracamy TYLKO raz przy rejestracji ({@code POST /auth/register}).</li>
 *   <li>W bazie przechowujemy {@code SHA256(klucz)} w kolumnie {@code key_hash}.</li>
 *   <li>Przy walidacji: hash przychodzacego klucza i porownaj do bazy.</li>
 *   <li>Zgubiony klucz = nie da sie odzyskac. User rejestruje nowy przez {@code POST /auth/register}.</li>
 * </ul>
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** SHA256(plainKey) w hex (64 znaki). Unique. */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "user_name", nullable = false, length = 255)
    private String userName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** Limit testow dziennie (default 1000). */
    @Column(name = "daily_limit", nullable = false)
    private Integer dailyLimit;

    /** Limit jednoczesnych polaczen SSE per klucz (default 20). */
    @Column(name = "stream_limit", nullable = false)
    private Integer streamLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** False = klucz zablokowany, zapytania zwracaja 403. */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
