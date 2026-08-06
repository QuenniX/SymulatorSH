package pl.smarthome.platform.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import pl.smarthome.platform.api.RateLimitInterceptor;

/**
 * Konfiguracja Spring MVC - rejestracja interceptorow.
 *
 * <p>Rate limit dodaje naglowki {@code X-RateLimit-*} do KAZDEJ odpowiedzi
 * pod {@code /api/**} i zwraca 429 przy przekroczeniu dla write ops.</p>
 *
 * <p>Wyklucza:</p>
 * <ul>
 *   <li>{@code /api/v1/auth/register} - zeby mozna bylo dostac klucz bez limitow</li>
 *   <li>{@code /swagger-ui/**}, {@code /v3/api-docs/**} - dokumentacja</li>
 *   <li>{@code /actuator/**} - health check dla load balancerow</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/register",   // musi byc bez limitu, inaczej nikt nie dostanie klucza
                        "/api/v1/tests/*/stream",  // SSE - dlugotrwale polaczenie, nie ma sensu liczyc
                        "/api/v1/batches/*/stream",// SSE - jak wyzej
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/actuator/**"
                );
    }
}
