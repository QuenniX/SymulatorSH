package pl.smarthome.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Globalna konfiguracja dokumentacji OpenAPI 3.0 dla platformy SymulatorSH.
 *
 * <p>Wynik dostepny pod:</p>
 * <ul>
 *   <li>{@code /swagger-ui.html} - interaktywna dokumentacja z "Try it out"</li>
 *   <li>{@code /v3/api-docs} - surowa specyfikacja JSON (do generowania klientow SDK)</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI symulatorShOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SymulatorSH API")
                        .version("1.0")
                        .description("""
                                # Platforma symulacyjna gospodarstw domowych — SymulatorSH

                                Publiczne API do programatycznego uruchamiania symulacji zużycia energii
                                w gospodarstwach domowych i analizy opłacalności taryf G11, G12 oraz RDN
                                (Rynek Dnia Następnego).

                                ## Kluczowe funkcje

                                - **Symulacja** — 12 typów urządzeń, konfigurowalne harmonogramy, przyspieszenie
                                  1000× względem czasu rzeczywistego (30-dniowy test w ~1 godzinę)
                                - **Ceny RDN** — pobierane automatycznie z API PSE (Polskie Sieci
                                  Elektroenergetyczne), aktualizowane codziennie
                                - **Analiza kosztu** — porównanie 3 taryf + statystyki ryzyka (VaR, CVaR)
                                - **Real-time** — Server-Sent Events do śledzenia postępu testu na żywo
                                - **Batch operations** — uruchamianie i śledzenie dziesiątek testów jednocześnie
                                - **Eksport** — CSV/XLSX do dalszej analizy w Excel/Python

                                ## Autentykacja

                                API jest **publiczne** — nie wymaga klucza. Nagłówek `X-API-Key` jest
                                **opcjonalny** i podnosi limit dziennych żądań (100 → 1000 testów/dzień)
                                oraz umożliwia śledzenie własnego użycia.

                                Aby dostać klucz: `POST /api/v1/auth/register` z imieniem i emailem.

                                ## Limity użycia (rate limiting)

                                - **Bez klucza:** 100 write-operations dziennie per IP
                                - **Z kluczem API:** 1000 write-operations dziennie per klucz
                                - Reset o północy UTC
                                - Nagłówki `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
                                  w każdej odpowiedzi

                                ## Kontekst pracy magisterskiej

                                Ten interfejs powstał jako część pracy magisterskiej badającej opłacalność
                                dynamicznych taryf energii dla polskich gospodarstw domowych. Autor:
                                Igor Guła, WEiI Politechnika Rzeszowska, 2026.
                                """)
                        .contact(new Contact()
                                .name("Igor Guła")
                                .email("igorgula17@gmail.com")
                                .url("https://github.com/QuenniX/SymulatorSH"))
                        .license(new License()
                                .name("Praca dyplomowa")
                                .url("https://prz.edu.pl/")))
                .servers(List.of(
                        new Server().url("/").description("Bieżący serwer (relatywnie do adresu przeglądarki)"),
                        new Server().url("http://localhost:8080").description("Środowisko lokalne (dev)"),
                        new Server().url("http://3.77.28.199").description("Środowisko produkcyjne (AWS EC2, Frankfurt)")
                ));
    }
}
