package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.smarthome.platform.domain.ApiKeyEntity;
import pl.smarthome.platform.service.ApiKeyService;

import java.time.Instant;
import java.util.UUID;

/**
 * Endpointy autentykacji - rejestracja klucza API i sprawdzenie wlasnego statusu.
 *
 * <p>Rejestracja jest publiczna (bez weryfikacji email). User dostaje klucz raz,
 * musi go zapisac - nie da sie go odzyskac pozniej.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Autentykacja", description = "Rejestracja kluczy API i sprawdzanie statusu konta")
public class AuthController {

    private final ApiKeyService apiKeyService;

    @PostMapping("/register")
    @Operation(
            summary = "Zarejestruj nowy klucz API",
            description = """
                Tworzy nowy klucz API dla uzytkownika programatycznego dostepu.
                Klucz jest zwracany TYLKO RAZ w polu apiKey - zapisz go bezpiecznie,
                nie da sie go odzyskac. W razie zguby zarejestruj nowy klucz.

                Klucz podnosi limit z 100 do 1000 testow dziennie oraz pozwala
                sledzic Twoje uzycie w endpoincie GET /auth/me.
                """
    )
    public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
        var result = apiKeyService.register(request.getName(), request.getEmail());

        RegisterResponse response = new RegisterResponse(
                result.apiKey(),
                result.keyId(),
                result.createdAt(),
                result.dailyLimit(),
                result.streamLimit(),
                "10 zapytan/sekunda"
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    @Operation(
            summary = "Sprawdz swoj status i limity",
            description = """
                Zwraca informacje o kluczu podanym w naglowku X-API-Key:
                uzytkownik, data utworzenia, aktualne uzycie i pozostaly limit dzienny.

                Bez naglowka X-API-Key zwraca 401.
                """
    )
    public ResponseEntity<?> me(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("UNAUTHORIZED", "Brak naglowka X-API-Key"));
        }
        return apiKeyService.getByPlainKey(apiKey)
                .filter(ApiKeyEntity::getIsActive)
                .<ResponseEntity<?>>map(entity -> ResponseEntity.ok(new MeResponse(
                        entity.getUserName(),
                        entity.getEmail(),
                        entity.getCreatedAt(),
                        entity.getLastUsedAt(),
                        entity.getDailyLimit(),
                        entity.getStreamLimit(),
                        entity.getIsActive()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorBody("INVALID_KEY", "Klucz nieznany lub zablokowany")));
    }

    // ==========================================================
    //  Request / Response DTO
    // ==========================================================

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "Imie jest wymagane")
        @Size(min = 2, max = 255, message = "Imie musi miec od 2 do 255 znakow")
        private String name;

        @NotBlank(message = "Email jest wymagany")
        @Email(message = "Nieprawidlowy format email")
        @Size(max = 255)
        private String email;
    }

    public record RegisterResponse(
            String apiKey,
            UUID keyId,
            Instant createdAt,
            Integer dailyLimit,
            Integer streamLimit,
            String rateLimit
    ) { }

    public record MeResponse(
            String userName,
            String email,
            Instant createdAt,
            Instant lastUsedAt,
            Integer dailyLimit,
            Integer streamLimit,
            Boolean isActive
    ) { }

    public record ErrorBody(String code, String message) { }
}
