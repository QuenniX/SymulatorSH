package pl.smarthome.platform.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.MeasurementPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
}
