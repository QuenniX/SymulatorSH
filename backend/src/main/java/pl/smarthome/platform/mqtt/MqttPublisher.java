package pl.smarthome.platform.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Klient MQTT do publikowania pomiarów.
 *
 * Symulatory urządzeń (uruchomione w tym samym procesie) publikują tutaj
 * swoje pomiary mocy, które następnie odbierane są przez MqttSubscriber
 * i przekazywane do InfluxDB.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MqttPublisher {

    private final MqttConfig config;
    private final ObjectMapper objectMapper;

    private MqttClient client;

    @PostConstruct
    public void connect() {
        try {
            String clientId = config.getClientIdPrefix() + "-pub-" + UUID.randomUUID();
            client = new MqttClient(config.getBrokerUrl(), clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);

            client.connect(options);
            log.info("MqttPublisher połączony z brokerem: {}", config.getBrokerUrl());
        } catch (MqttException e) {
            log.error("MqttPublisher: nie udało się połączyć z brokerem {} - {}",
                    config.getBrokerUrl(), e.getMessage());
        }
    }

    public void publishPower(UUID testId, String deviceId, double powerW, long timestampMs) {
        if (client == null || !client.isConnected()) {
            log.debug("MqttPublisher nieaktywny - skip publish");
            return;
        }
        String topic = String.format("tests/%s/devices/%s/energy", testId, deviceId);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("device_id", deviceId);
            payload.put("power_w", powerW);
            payload.put("timestamp_ms", timestampMs);

            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            message.setQos(config.getQos());
            client.publish(topic, message);
        } catch (Exception e) {
            log.warn("Błąd publikacji MQTT na {}: {}", topic, e.getMessage());
        }
    }

    @PreDestroy
    public void disconnect() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
                client.close();
                log.info("MqttPublisher rozłączony");
            } catch (MqttException e) {
                log.warn("Błąd rozłączania MqttPublisher: {}", e.getMessage());
            }
        }
    }
}
