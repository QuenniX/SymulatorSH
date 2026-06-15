package pl.smarthome.platform.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
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

    @Override
    public void afterPropertiesSet() {
        this.writeApi = client.makeWriteApi();
        log.info("InfluxWriter zainicjalizowany dla bucketu '{}', org '{}'",
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

    @Override
    public void destroy() {
        if (writeApi != null) {
            writeApi.close();
        }
    }
}
