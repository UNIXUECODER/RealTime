package dev.realtime.tenancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import dev.realtime.auth.CurrentTenant;
import dev.realtime.ingest.IngestOutcome;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the test-event endpoint (M6b) in isolation — {@link ChannelService} is
 * mocked, so this runs with no Redis or Postgres dependency. Written specifically to
 * pin down the genuinely-bodyless-request bug found during manual testing: a
 * @RequestBody Mono<String> with the default required = true throws
 * ServerWebInputException for a request with no body at all (distinct from an
 * explicit empty string), before the handler's own default-payload fallback ever
 * runs. See WebhookController for the identical, separately-found and separately-
 * fixed instance of the same pattern.
 */
@WebFluxTest(ChannelController.class)
class ChannelControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ChannelService channelService;

    @MockitoBean
    private CurrentTenant currentTenant;

    @BeforeEach
    void authenticatedByDefault() {
        when(currentTenant.id()).thenReturn(Mono.just(10L));
    }

    @Test
    void genuinelyBodylessRequestFallsBackToTheDefaultPayload() {
        when(channelService.sendTestEvent(eq(10L), eq("ch1"), anyString()))
                .thenReturn(Mono.just(IngestOutcome.ACCEPTED));

        webTestClient.post()
                .uri("/channels/ch1/test-event")
                // No .bodyValue(...)/.body(...) at all — this is the case that actually
                // exercises @RequestBody(required = false); an explicit empty string
                // would exercise a different, already-safe code path.
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.outcome").isEqualTo("ACCEPTED");

        verify(channelService).sendTestEvent(eq(10L), eq("ch1"), anyString());
    }

    @Test
    void customPayloadIsPassedThroughUnchanged() {
        when(channelService.sendTestEvent(anyLong(), anyString(), any()))
                .thenReturn(Mono.just(IngestOutcome.FILTERED));

        webTestClient.post()
                .uri("/channels/ch1/test-event")
                .bodyValue("{\"type\":\"payment.failed\",\"amount\":500}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.outcome").isEqualTo("FILTERED");

        verify(channelService).sendTestEvent(10L, "ch1", "{\"type\":\"payment.failed\",\"amount\":500}");
    }

    @Test
    void invalidJsonBodyIsRejectedWithBadRequestNotA500() {
        webTestClient.post()
                .uri("/channels/ch1/test-event")
                .bodyValue("{not valid json")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
