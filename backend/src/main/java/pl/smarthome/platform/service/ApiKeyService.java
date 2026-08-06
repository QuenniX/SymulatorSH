package pl.smarthome.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.smarthome.platform.domain.ApiKeyEntity;
import pl.smarthome.platform.repository.ApiKeyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Zarzadzanie kluczami API.
 *
 * <p>Generuje bezpieczne klucze w formacie {@code sk_live_...} (32 znaki hex).
 * W bazie przechowuje tylko SHA256 klucza - plain text zwracamy tylko raz
 * podczas rejestracji.</p>
 *
 * <p>Domyslne limity: 1000 testow/dzien, 20 rownoczesnych strumieni SSE.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private static final int DEFAULT_DAILY_LIMIT = 1000;
    private static final int DEFAULT_STREAM_LIMIT = 20;
    /** Prefix zeby wizualnie odroznic klucze SymulatorSH od innych API. */
    private static final String KEY_PREFIX = "sk_live_";
    /** Ile bajtow losowosci (32 bajty = 256 bit = mocna entropia). */
    private static final int KEY_ENTROPY_BYTES = 32;

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Rejestruje nowego uzytkownika. Generuje klucz, zapisuje jego HASH w bazie,
     * zwraca plain text klucza (widoczny raz - user musi zapisac).
     */
    @Transactional
    public RegistrationResult register(String userName, String email) {
        validateInput(userName, email);

        String plainKey = generateKey();
        String hash = sha256Hex(plainKey);

        ApiKeyEntity entity = ApiKeyEntity.builder()
                .id(UUID.randomUUID())
                .keyHash(hash)
                .userName(userName.trim())
                .email(email.trim().toLowerCase())
                .dailyLimit(DEFAULT_DAILY_LIMIT)
                .streamLimit(DEFAULT_STREAM_LIMIT)
                .createdAt(Instant.now())
                .isActive(true)
                .build();
        apiKeyRepository.save(entity);

        log.info("Zarejestrowano nowy klucz API dla {} ({})", userName, email);

        return new RegistrationResult(
                plainKey,
                entity.getId(),
                entity.getCreatedAt(),
                entity.getDailyLimit(),
                entity.getStreamLimit()
        );
    }

    /**
     * Waliduje klucz przychodzacy w nagłowku X-API-Key. Aktualizuje last_used_at
     * przy okazji (fire-and-forget, nie blokuje response).
     *
     * @return encja klucza jesli waliduje sie ok, {@code Optional.empty()} jesli klucz
     *         nieznany albo zablokowany
     */
    @Transactional
    public Optional<ApiKeyEntity> validate(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(plainKey);
        Optional<ApiKeyEntity> found = apiKeyRepository.findByKeyHash(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKeyEntity entity = found.get();
        if (!entity.getIsActive()) {
            return Optional.empty();  // zablokowany
        }
        // Aktualizuj last_used - fire and forget
        entity.setLastUsedAt(Instant.now());
        apiKeyRepository.save(entity);
        return Optional.of(entity);
    }

    /**
     * Zwraca metadane klucza dla endpointu GET /auth/me. Bez plain text klucza -
     * ten jest znany tylko przy rejestracji.
     */
    public Optional<ApiKeyEntity> getByPlainKey(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) return Optional.empty();
        return apiKeyRepository.findByKeyHash(sha256Hex(plainKey));
    }

    // ==========================================================
    //  Helpery prywatne
    // ==========================================================

    private String generateKey() {
        byte[] bytes = new byte[KEY_ENTROPY_BYTES];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nie dostepne w JVM", e);
        }
    }

    private void validateInput(String userName, String email) {
        if (userName == null || userName.trim().length() < 2) {
            throw new IllegalArgumentException("Imie/nazwa musi miec minimum 2 znaki");
        }
        if (email == null || !email.contains("@") || email.length() > 255) {
            throw new IllegalArgumentException("Nieprawidlowy adres email");
        }
    }

    /**
     * Wynik rejestracji - plain text klucza (widoczny raz!) + metadata.
     */
    public record RegistrationResult(
            String apiKey,
            UUID keyId,
            Instant createdAt,
            Integer dailyLimit,
            Integer streamLimit
    ) { }
}
