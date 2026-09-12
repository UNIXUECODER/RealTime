package dev.realtime.ingest;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.tenancy.Channel;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Exercises the controller in isolation — {@link IngestService} and {@link
 * WebhookAuthService} are both mocked, so these run with no Redis or Postgres
 * dependency. The real end-to-end path (does an event actually land in the stream,
 * exactly once, filtered and authenticated correctly) is verified manually.
 */
@WebFluxTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private IngestService ingestService;

    @MockitoBean
    private WebhookAuthService webhookAuthService;

    @BeforeEach
    void authSucceedsByDefault() {
        // Every existing test here predates M5 and isn't testing auth — default to a
        // successful authentication so they keep exercising exactly what they always did.
        Channel channel = Channel.builder()
                .id(1L).tenantId(1L).publicId("test-channel").name("test")
                .retentionDays(7).createdAt(Instant.now())
                .build();
        when(webhookAuthService.authenticate(anyString(), any())).thenReturn(Mono.just(channel));
    }

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

    @Test
    void missingApiKeyIsRejected() {
        when(webhookAuthService.authenticate(anyString(), any())).thenReturn(
                Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing API key")));

        webTestClient.post()
                .uri("/webhook/test-channel")
                .bodyValue("{\"type\":\"test\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void unknownChannelIsRejectedWithNotFound() {
        when(webhookAuthService.authenticate(anyString(), any())).thenReturn(
                Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel")));

        webTestClient.post()
                .uri("/webhook/does-not-exist")
                .header("X-Api-Key", "rtk_whatever")
                .bodyValue("{\"type\":\"test\"}")
                .exchange()
                .expectStatus().isNotFound();
    }
}

