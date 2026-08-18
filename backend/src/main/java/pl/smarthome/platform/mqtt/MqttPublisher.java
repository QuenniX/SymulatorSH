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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Licznik pomiarow porzuconych, bo broker byl rozlaczony albo publish rzucil.
     * Bez tego utrata danych jest calkowicie cicha (log.debug) - a przy speedFactor=720
     * i puli 4 watkow okno rekonekcji potrafi zjesc setki pomiarow, co objawia sie
     * dopiero jako "zagregowano 693 godzin zamiast 720" na koncu analizy.
     */
    private final AtomicLong droppedPublishes = new AtomicLong();

    /** Liczba pomiarow porzuconych od startu aplikacji. Do walidacji przebiegu partii. */
    public long getDroppedPublishes() {
        return droppedPublishes.get();
    }

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
            // Domyslny limit Paho to 10 wiadomosci "w locie". Przy QoS 1 kazda czeka na
            // PUBACK, a petla symulacji publikuje wszystkie urzadzenia jednego testu
            // w jednym przebiegu (15 urzadzen x 4 rownolegle testy = do 60 publikacji
            // naraz). Po przekroczeniu limitu publish() rzuca "Too many publishes in
            // progress" i pomiar przepada.
            //
            // Utrata NIE jest losowa: okno zapelnia sie w trakcie petli po urzadzeniach,
            // wiec gina konsekwentnie urzadzenia z KONCA listy. Generator dopisuje
            // grzejniki na koncu konfiguracji zimowej, czyli tracone bylo zuzycie
            // najwiekszych odbiornikow sezonu grzewczego (heater_livingroom 2000 W,
            // heater_bedroom 1500 W) - blad systematyczny zanizajacy zuzycie zimowe.
            options.setMaxInflight(1000);

            client.connect(options);
            log.info("MqttPublisher połączony z brokerem: {}", config.getBrokerUrl());
        } catch (MqttException e) {
            log.error("MqttPublisher: nie udało się połączyć z brokerem {} - {}",
                    config.getBrokerUrl(), e.getMessage());
        }
    }

    public void publishPower(UUID testId, String deviceId, double powerW, long timestampMs) {
        if (client == null || !client.isConnected()) {
            long n = droppedPublishes.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warn("MqttPublisher rozlaczony - POMIAR UTRACONY (lacznie {} od startu). "
                        + "Wynik testu bedzie niekompletny.", n);
            }
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
            long n = droppedPublishes.incrementAndGet();
            log.warn("Blad publikacji MQTT na {} (utracono lacznie {}): {}", topic, n, e.getMessage());
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
