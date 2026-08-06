package pl.smarthome.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pl.smarthome.platform.domain.ApiKeyEntity;
import pl.smarthome.platform.service.ApiKeyService;
import pl.smarthome.platform.service.RateLimitService;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Interceptor dodajacy rate limiting do wszystkich zapytan API.
 *
 * <p><b>Zachowanie:</b></p>
 * <ol>
 *   <li>Wyciaga naglowek X-API-Key (opcjonalny)</li>
 *   <li>Waliduje klucz - jesli OK, uzywa limitu z konta ({@code daily_limit}). Jesli klucz zly, traktuje jako anonim.</li>
 *   <li>Bez klucza - uzywa IP klienta jako identyfikator, limit anonim.</li>
 *   <li>Rate limit inkrementowany <b>tylko dla write ops (POST/PUT/DELETE)</b>. GET nie liczy.</li>
 *   <li>Do KAZDEJ odpowiedzi dodaje naglowki {@code X-RateLimit-*}</li>
 *   <li>Przy przekroczeniu zwraca HTTP 429 z JSON + {@code Retry-After} header</li>
 * </ol>
 *
 * <p>Wykluczone z rate limiting (via WebMvcConfig):</p>
 * <ul>
 *   <li>{@code POST /api/v1/auth/register} - zeby mozna bylo dostac klucz bez limitow</li>
 *   <li>{@code /swagger-ui/**} - dokumentacja</li>
 *   <li>{@code /v3/api-docs/**} - OpenAPI JSON</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String HEADER_API_KEY = "X-API-Key";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader(HEADER_API_KEY);
        String method = request.getMethod();
        boolean isWrite = WRITE_METHODS.contains(method);

        // 1. Wyznacz identyfikator klienta + limit
        String identifier;
        int limit;

        if (apiKey != null && !apiKey.isBlank()) {
            Optional<ApiKeyEntity> validKey = apiKeyService.validate(apiKey);
            if (validKey.isPresent()) {
                identifier = "key:" + validKey.get().getId();
                limit = validKey.get().getDailyLimit();
            } else {
                // Klucz podany ale niewazny - traktuj jako anonim
                identifier = "ip:" + clientIp(request);
                limit = rateLimitService.anonymousLimit();
            }
        } else {
            identifier = "ip:" + clientIp(request);
            limit = rateLimitService.anonymousLimit();
        }

        // 2. Sprawdz i inkrementuj (albo tylko podejrzyj dla GET)
        RateLimitService.LimitStatus status = isWrite
                ? rateLimitService.checkAndIncrement(identifier, limit)
                : rateLimitService.peek(identifier, limit);

        // 3. Dodaj naglowki do KAZDEJ odpowiedzi
        response.setHeader("X-RateLimit-Limit", String.valueOf(status.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(status.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(status.resetAt().getEpochSecond()));

        // 4. Jesli przekroczono - zwroc 429 i przerwij request
        if (status.exceeded() && isWrite) {
            long retryAfter = status.secondsUntilReset();
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            String message = apiKey != null
                    ? "Przekroczono dzienny limit dla tego klucza API. Reset o polnocy UTC."
                    : "Przekroczono dzienny limit dla anonimowych zapytan (100/dzien per IP). "
                      + "Zarejestruj klucz przez POST /api/v1/auth/register zeby dostac limit 1000/dzien.";

            Map<String, Object> errorBody = Map.of(
                    "error", Map.of(
                            "code", "RATE_LIMIT_EXCEEDED",
                            "message", message,
                            "statusCode", 429,
                            "timestamp", Instant.now().toString(),
                            "details", Map.of(
                                    "limit", status.limit(),
                                    "used", status.used(),
                                    "remaining", 0,
                                    "resetAt", status.resetAt().toString(),
                                    "retryAfterSeconds", retryAfter
                            )
                    )
            );
            response.getWriter().write(objectMapper.writeValueAsString(errorBody));
            response.getWriter().flush();

            log.warn("Rate limit exceeded dla {} ({} {}). Limit: {}/dzien",
                    identifier, method, request.getRequestURI(), status.limit());
            return false;  // przerwij chain
        }

        return true;  // przepusc request dalej
    }

    /**
     * Wyciaga IP klienta. Uwzglednia X-Forwarded-For (jesli za reverse proxy jak Caddy/nginx).
     */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // XFF moze byc lista IP przez proxy - bierzemy pierwszy (rzeczywisty klient)
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
