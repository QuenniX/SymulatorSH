package pl.smarthome.platform.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;
import pl.smarthome.platform.influx.InfluxWriter;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subskrybent MQTT - odbiera pomiary opublikowane przez symulatory urządzeń
 * (działające w tym samym procesie albo zewnętrznie) i zapisuje je do InfluxDB.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MqttSubscriber {

    private static final Pattern TOPIC_PATTERN =
            Pattern.compile("tests/([a-f0-9-]+)/devices/([\\w-]+)/energy");

    private static final String SUBSCRIPTION_TOPIC = "tests/+/devices/+/energy";

    private final MqttConfig config;
    private final ObjectMapper objectMapper;
    private final InfluxWriter influxWriter;

    private MqttClient client;

    @PostConstruct
    public void connect() {
        try {
            String clientId = config.getClientIdPrefix() + "-sub-" + UUID.randomUUID();
            client = new MqttClient(config.getBrokerUrl(), clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);

            client.connect(options);
            client.subscribe(SUBSCRIPTION_TOPIC, config.getQos(), this::handle);

            log.info("MqttSubscriber połączony z brokerem: {} (subskrypcja: {})",
                    config.getBrokerUrl(), SUBSCRIPTION_TOPIC);
        } catch (MqttException e) {
            log.error("MqttSubscriber: nie udało się połączyć - {}", e.getMessage());
        }
    }

    private void handle(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) throws Exception {
        Matcher m = TOPIC_PATTERN.matcher(topic);
        if (!m.matches()) {
            log.debug("Pominięto wiadomość z nieoczekiwanego tematu: {}", topic);
            return;
        }
        UUID testId = UUID.fromString(m.group(1));
        String deviceId = m.group(2);

        JsonNode payload = objectMapper.readTree(message.getPayload());
        double powerW = payload.has("power_w") ? payload.get("power_w").asDouble() : 0;
        long timestampMs = payload.has("timestamp_ms")
                ? payload.get("timestamp_ms").asLong()
                : System.currentTimeMillis();

        influxWriter.writePower(testId, deviceId, powerW, Instant.ofEpochMilli(timestampMs));
    }

    @PreDestroy
    public void disconnect() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
                client.close();
                log.info("MqttSubscriber rozłączony");
            } catch (MqttException e) {
                log.warn("Błąd rozłączania MqttSubscriber: {}", e.getMessage());
            }
        }
    }
}
