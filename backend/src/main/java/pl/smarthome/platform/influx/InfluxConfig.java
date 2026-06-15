package pl.smarthome.platform.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class InfluxConfig {

    @Value("${platform.influx.url}")
    private String url;

    @Value("${platform.influx.token}")
    private String token;

    @Value("${platform.influx.org}")
    private String org;

    @Value("${platform.influx.bucket}")
    private String bucket;

    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient() {
        return InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
    }
}
