package pl.smarthome.platform.pse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Klient HTTP dla publicznego API PSE (Polskich Sieci Elektroenergetycznych).
 *
 * Endpoint: /api/rce-pln - Rynkowa Cena Energii oparta o RDN.
 * Filter: ?$filter=business_date eq 'YYYY-MM-DD'
 *
 * Wazne: PSE zwraca <b>96 rekordow kwadransowych</b> na dobe (co 15 min).
 * Ten klient agreguje je do 24 godzinowych srednich.
 *
 * Struktura pojedynczego rekordu z PSE:
 * <pre>
 * {
 *   "dtime": "2025-07-23 00:15:00",
 *   "period": "00:00 - 00:15",       -- zakres kwadransa, uzywamy do godziny
 *   "rce_pln": 415.59,                -- cena w zl/MWh
 *   "business_date": "2025-07-23"
 * }
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PseApiClient {

    @Value("${platform.pse.base-url:https://api.raporty.pse.pl}")
    private String baseUrl;

    @Value("${platform.pse.endpoint:/api/rce-pln}")
    private String endpoint;

    private final ObjectMapper objectMapper;

    /**
     * Pobiera 24 godzinowe usrednione ceny dla wybranej doby.
     * Agreguje 96 rekordow kwadransowych do 24 godzinowych srednich.
     */
    public List<HourPrice> fetchPrices(LocalDate deliveryDate) {
        String dateStr = deliveryDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String url = String.format("%s%s?$filter=business_date eq '%s'", baseUrl, endpoint, dateStr);
        log.info("PSE API request: {}", url);

        RestClient client = RestClient.create();
        try {
            String responseJson = client.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            return aggregateQuartersToHours(responseJson, deliveryDate);
        } catch (RestClientException e) {
            log.error("PSE API blad dla {}: {}", deliveryDate, e.getMessage());
            throw new RuntimeException("Nie udalo sie pobrac cen z PSE API", e);
        }
    }

    /**
     * Parsuje 96 rekordow kwadransowych i grupuje je po godzinach (0-23),
     * usredniajac ceny w kazdej godzinie.
     */
    private List<HourPrice> aggregateQuartersToHours(String json, LocalDate deliveryDate) {
        // Mapa: godzina (0-23) -> lista cen kwadransowych z tej godziny
        Map<Integer, List<BigDecimal>> byHour = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray()) {
                log.warn("PSE API: brak pola 'value' w odpowiedzi");
                return List.of();
            }
            Iterator<JsonNode> items = valueArr.elements();
            while (items.hasNext()) {
                JsonNode item = items.next();
                Integer hour = extractHour(item);
                BigDecimal price = extractPrice(item);
                if (hour != null && price != null && hour >= 0 && hour <= 23) {
                    byHour.computeIfAbsent(hour, h -> new ArrayList<>()).add(price);
                }
            }
        } catch (Exception e) {
            log.error("Blad parsowania odpowiedzi PSE: {}", e.getMessage());
            throw new RuntimeException("Nie udalo sie sparsowac odpowiedzi PSE", e);
        }

        // Usrednij ceny w kazdej godzinie
        List<HourPrice> result = new ArrayList<>();
        TreeMap<Integer, List<BigDecimal>> sortedByHour = new TreeMap<>(byHour);
        for (Map.Entry<Integer, List<BigDecimal>> entry : sortedByHour.entrySet()) {
            int hour = entry.getKey();
            List<BigDecimal> prices = entry.getValue();
            BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(prices.size()), 2, RoundingMode.HALF_UP);
            result.add(new HourPrice(deliveryDate, hour, avg));
        }

        log.info("PSE API: pobrano {} kwadransow dla {}, zagregowano do {} godzin",
                byHour.values().stream().mapToInt(List::size).sum(),
                deliveryDate,
                result.size());
        return result;
    }

    /**
     * Wyciaga godzine (0-23) z pola 'period'.
     * Format: "HH:MM - HH:MM" gdzie pierwsza godzina to nasza godzina docelowa.
     * Przyklady:
     *   "00:00 - 00:15" -> godzina 0
     *   "00:45 - 01:00" -> godzina 0 (nadal godzina 0, bo 00:45-01:00 to kwadrans godziny 0)
     *   "01:00 - 01:15" -> godzina 1
     *   "23:45 - 24:00" -> godzina 23
     */
    private Integer extractHour(JsonNode item) {
        if (item.has("period")) {
            String period = item.get("period").asText();
            String[] parts = period.split(" - ");
            if (parts.length == 2) {
                String startTime = parts[0].trim();  // "00:00"
                String[] hm = startTime.split(":");
                if (hm.length == 2) {
                    return Integer.parseInt(hm[0]);
                }
            }
        }
        return null;
    }

    /** Wyciaga cene z pola rce_pln. */
    private BigDecimal extractPrice(JsonNode item) {
        if (item.has("rce_pln")) {
            return new BigDecimal(item.get("rce_pln").asText());
        }
        return null;
    }

    /** Jedna godzinowa cena - struktura pomocnicza. */
    public record HourPrice(LocalDate deliveryDate, Integer hour, BigDecimal pricePlnMwh) { }
}
