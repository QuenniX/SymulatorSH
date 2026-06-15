package pl.smarthome.platform.mqtt;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class MqttConfig {

    @Value("${platform.mqtt.broker-url}")
    private String brokerUrl;

    @Value("${platform.mqtt.client-id-prefix}")
    private String clientIdPrefix;

    @Value("${platform.mqtt.qos:1}")
    private int qos;
}
