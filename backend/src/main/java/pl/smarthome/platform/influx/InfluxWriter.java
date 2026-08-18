package pl.smarthome.platform.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.events.WriteErrorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InfluxWriter implements InitializingBean, DisposableBean {

    private final InfluxDBClient client;
    private final InfluxConfig config;

    private WriteApi writeApi;

    /**
     * Licznik bledow zapisu. WriteApi jest ASYNCHRONICZNE i batchujace - bez
     * zarejestrowanego listenera bledy (rate limit darmowego planu, "HTTP write
     * interrupted") gina po cichu, a brak danych widac dopiero jako niepelna
     * liczbe godzin w analizie.
     */
    private final java.util.concurrent.atomic.AtomicLong writeErrors =
            new java.util.concurrent.atomic.AtomicLong();

    /** Liczba bledow zapisu do InfluxDB od startu aplikacji. Do walidacji przebiegu partii. */
    public long getWriteErrors() {
        return writeErrors.get();
    }

    @Override
    public void afterPropertiesSet() {
        this.writeApi = client.makeWriteApi();
        writeApi.listenEvents(WriteErrorEvent.class, e -> {
            long n = writeErrors.incrementAndGet();
            log.error("InfluxDB WRITE ERROR #{} - POMIARY UTRACONE: {}", n,
                    e.getThrowable() != null ? e.getThrowable().getMessage() : "brak szczegolow");
        });
        log.info("InfluxWriter zainicjalizowany dla bucketu '{}', org '{}' (listenery bledow aktywne)",
                config.getBucket(), config.getOrg());
    }

    public void writePower(UUID testId, String deviceId, double powerW, Instant timestamp) {
        Point point = Point.measurement("power")
                .addTag("test_id", testId.toString())
                .addTag("device_id", deviceId)
                .addField("power_w", powerW)
                .time(timestamp, WritePrecision.MS);
        writeApi.writePoint(config.getBucket(), config.getOrg(), point);
    }

    /** Wymusza wyslanie zbuforowanych punktow. Wolac po zakonczeniu testu. */
    public void flush() {
        if (writeApi != null) {
            writeApi.flush();
        }
    }

    @Override
    public void destroy() {
        if (writeApi != null) {
            writeApi.flush();
            writeApi.close();
        }
    }
}
