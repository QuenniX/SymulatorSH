package pl.smarthome.platform.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.MeasurementPoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InfluxQueryService {

    private final InfluxDBClient client;
    private final InfluxConfig config;

    public List<MeasurementPoint> getMeasurements(UUID testId, String deviceFilter) {
        String deviceFilterFlux = deviceFilter == null
                ? ""
                : "  |> filter(fn: (r) => r.device_id == \"" + deviceFilter + "\")\n";

        // Range obejmuje też przyszłość - na wypadek testów z przesuniętymi
        // znacznikami (starsze dane sprzed zmiany w TestRunner).
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: -30d, stop: 365d)
                  |> filter(fn: (r) => r._measurement == "power")
                  |> filter(fn: (r) => r.test_id == "%s")
                %s  |> filter(fn: (r) => r._field == "power_w")
                  |> keep(columns: ["_time", "device_id", "_value"])
                  |> sort(columns: ["_time"])
                """, config.getBucket(), testId, deviceFilterFlux);

        List<MeasurementPoint> points = new ArrayList<>();
        List<FluxTable> tables = client.getQueryApi().query(flux, config.getOrg());
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant time = record.getTime();
                String deviceId = (String) record.getValueByKey("device_id");
                Object value = record.getValue();
                double powerW = value instanceof Number n ? n.doubleValue() : 0.0;
                points.add(MeasurementPoint.builder()
                        .timestamp(time)
                        .deviceId(deviceId)
                        .powerW(powerW)
                        .build());
            }
        }
        log.debug("Pobrano {} punktów dla testu {}", points.size(), testId);
        return points;
    }

    /**
     * Zwraca zuzycie energii w kWh dla testu, zagregowane <b>per godzina</b>
     * w strefie <b>Europe/Warsaw</b> (bo ceny RDN sa w czasie polskim).
     *
     * <p>Metodologia:</p>
     * <ol>
     *   <li>Flux robi aggregateWindow 1h + mean(power_w) dla kazdego urzadzenia osobno.</li>
     *   <li>W Javie sumujemy srednie moce wszystkich urzadzen w tej samej godzinie.</li>
     *   <li>Konwersja: kWh = suma_mocy_W * 1h / 1000.</li>
     * </ol>
     *
     * <p>Klucz mapy: godzina lokalna (Polska), np. 2024-03-15T14:00. Wartosc: kWh.</p>
     *
     * <p><b>Waga zalozenia:</b> mean(power_w) * 1h daje energie tylko gdy dane sa gestre
     * (np. co sekunde). Dla naszego symulatora ktory emituje pomiary co ~1s to jest OK.
     * Blad rzedu 1-2% w krancowych godzinach testu (start/koniec ktore nie pokrywaja
     * pelnej godziny).</p>
     *
     * <p><b>Cache:</b> wynik jest cache'owany per testId (in-memory ConcurrentMap).
     * Dla testu COMPLETED profil sie nie zmienia, wiec cache eliminuje zbedne
     * query'ki do InfluxDB (rate limit darmowego planu ~300 req/min).</p>
     */
    @Cacheable(value = "hourlyEnergy", key = "#testId")
    public Map<LocalDateTime, BigDecimal> getHourlyEnergyKwh(UUID testId) {
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: -30d, stop: 365d)
                  |> filter(fn: (r) => r._measurement == "power")
                  |> filter(fn: (r) => r.test_id == "%s")
                  |> filter(fn: (r) => r._field == "power_w")
                  |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
                  |> keep(columns: ["_time", "device_id", "_value"])
                """, config.getBucket(), testId);

        // Krok 1: zbierz srednie moce (W) per godzina lokalna, sumujac po urzadzeniach.
        Map<LocalDateTime, BigDecimal> hourlyMeanPowerWatts = new TreeMap<>();
        ZoneId polandZone = ZoneId.of("Europe/Warsaw");

        List<FluxTable> tables = client.getQueryApi().query(flux, config.getOrg());
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant time = record.getTime();
                if (time == null) continue;

                Object rawValue = record.getValue();
                if (!(rawValue instanceof Number number)) continue;
                double meanPowerW = number.doubleValue();

                // Zaokraglenie do poczatku godziny w strefie polskiej (kluczowe dla mapowania
                // do cen RDN, ktore sa publikowane per godzine w czasie polskim).
                LocalDateTime hourStart = time.atZone(polandZone)
                        .truncatedTo(ChronoUnit.HOURS)
                        .toLocalDateTime();

                hourlyMeanPowerWatts.merge(
                        hourStart,
                        BigDecimal.valueOf(meanPowerW),
                        BigDecimal::add
                );
            }
        }

        // Krok 2: konwersja W -> kWh (srednia moc * 1h / 1000).
        Map<LocalDateTime, BigDecimal> hourlyKwh = new TreeMap<>();
        for (Map.Entry<LocalDateTime, BigDecimal> entry : hourlyMeanPowerWatts.entrySet()) {
            BigDecimal kwh = entry.getValue()
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            hourlyKwh.put(entry.getKey(), kwh);
        }

        log.info("Test {}: zagregowano {} godzin zuzycia energii", testId, hourlyKwh.size());
        return hourlyKwh;
    }
}
