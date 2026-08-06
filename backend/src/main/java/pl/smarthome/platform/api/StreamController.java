package pl.smarthome.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.smarthome.platform.service.StreamEventPublisher;

import java.util.UUID;

/**
 * Endpointy Server-Sent Events (SSE) - aktualizacje na zywo dla klientow API.
 *
 * <p>Klient Python otwiera polaczenie streaming HTTP i dostaje eventy jak tylko
 * zmienia sie status testu (QUEUED -> RUNNING -> progress 25/50/75% -> COMPLETED).</p>
 *
 * <p>Format zgodny z W3C Server-Sent Events - kazdy event to linia
 * {@code data: &#123;JSON&#125;} + pusta linia separator. Klient parsuje przez
 * {@code requests.get(url, stream=True).iter_lines()}.</p>
 *
 * <p>Keep-alive co 30 sekund (pusty komentarz) zeby proxy/NAT nie zamknely polaczenia.</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Streamy real-time", description = "Server-Sent Events - aktualizacje statusu testow i partii na zywo")
public class StreamController {

    private final StreamEventPublisher publisher;

    @GetMapping(value = "/tests/{testId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Real-time stream statusu pojedynczego testu (SSE)",
            description = """
                Otwiera dlugotrwale polaczenie HTTP i wysyla eventy Server-Sent Events kiedy:

                1. Klient subskrybuje - dostaje od razu aktualny stan
                2. Test przechodzi z QUEUED do RUNNING
                3. Test raportuje postep (co ~25%): 25, 50, 75, 100
                4. Test konczy sie: COMPLETED albo FAILED

                Kazdy event to JSON z polami {testId, status, progress, message, timestamp}.

                Klient Python:
                    response = requests.get(url, stream=True)
                    for line in response.iter_lines():
                        if line.startswith(b'data:'):
                            event = json.loads(line[5:])
                            print(event['status'], event.get('progress', '-'))

                Keep-alive co 30s. Timeout 30 min.
                """
    )
    public SseEmitter streamTest(@PathVariable("testId") UUID testId) {
        log.info("SSE subskrypcja test={}", testId);
        return publisher.subscribeTest(testId);
    }

    @GetMapping(value = "/batches/{batchId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Real-time stream stanu partii testow (SSE)",
            description = """
                Otwiera dlugotrwale polaczenie HTTP i wysyla eventy agregacyjne dla calej
                partii kiedy KTORYKOLWIEK z testow zmienia status.

                Kazdy event zawiera: {batchId, total, queued, running, completed, failed,
                cancelled, currentlyRunning[]} - snapshot calej partii.

                Idealny do sledzenia postepu batcha 24 testow jednym polaczeniem
                zamiast 24 osobnych stream'ow.

                Klient Python:
                    for line in response.iter_lines():
                        event = json.loads(line[5:])
                        print(f"{event['completed']}/{event['total']} zrobione")

                Keep-alive co 30s. Timeout 30 min.
                """
    )
    public SseEmitter streamBatch(@PathVariable("batchId") UUID batchId) {
        log.info("SSE subskrypcja batch={}", batchId);
        return publisher.subscribeBatch(batchId);
    }
}
