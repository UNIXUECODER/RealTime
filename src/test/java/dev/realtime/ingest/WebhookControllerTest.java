package dev.realtime.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Exercises the controller in isolation — {@link IngestService} is mocked, so these run
 * with no Redis dependency. The real end-to-end path (does an event actually land in the
 * stream, exactly once, filtered correctly) is verified manually: curl + redis-cli
 * (M1), and the two-client WS resume check (M2).
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
    void malformedJsonIsRejectedWithRequestId() {
        webTestClient.post()
                .uri("/webhook/test-channel")
                .bodyValue("{not valid json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    void newEventIsAccepted() {
        when(ingestService.ingest(anyString(), anyString())).thenReturn(Mono.just(IngestOutcome.ACCEPTED));

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
        when(ingestService.ingest(anyString(), anyString())).thenReturn(Mono.just(IngestOutcome.DUPLICATE));

        webTestClient.post()
                .uri("/webhook/test-channel")
                .bodyValue("{\"type\":\"test\"}")
                .exchange()
                .expectStatus().isEqualTo(202);
    }

    @Test
    void filteredEventStillReturnsAccepted() {
        // Same reasoning as duplicates — a channel choosing not to keep an event isn't
        // an error the sender should retry over (spec §5/§6).
        when(ingestService.ingest(anyString(), anyString())).thenReturn(Mono.just(IngestOutcome.FILTERED));

        webTestClient.post()
                .uri("/webhook/test-channel")
                .bodyValue("{\"type\":\"test\"}")
                .exchange()
                .expectStatus().isEqualTo(202);
    }
}
