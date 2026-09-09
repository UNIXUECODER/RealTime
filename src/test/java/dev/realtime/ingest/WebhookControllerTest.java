package dev.realtime.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Exercises the controller in isolation — {@link IngestService} is mocked, so these run
 * with no Redis dependency. The real end-to-end path (does an event actually land in the
 * stream, exactly once) is verified manually per M1's exit criteria: curl + redis-cli.
 */
@WebFluxTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private IngestService ingestService;

    @Test
    void blankBodyIsRejected() {
        webTestClient.post()
                .uri("/webhook/test-channel")
                .bodyValue("")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void newEventIsAccepted() {
        when(ingestService.ingest(anyString(), anyString())).thenReturn(Mono.just(true));

        webTestClient.post()
                .uri("/webhook/test-channel")
                .bodyValue("{\"type\":\"test\"}")
                .exchange()
                .expectStatus().isEqualTo(202);
    }

    @Test
    void duplicateEventStillReturnsAccepted() {
        // A deduped retry is not an error from the sender's perspective — it's exactly
        // the outcome that stops them from retrying further (spec §4).
        when(ingestService.ingest(anyString(), anyString())).thenReturn(Mono.just(false));

        webTestClient.post()
                .uri("/webhook/test-channel")
                .bodyValue("{\"type\":\"test\"}")
                .exchange()
                .expectStatus().isEqualTo(202);
    }
}
