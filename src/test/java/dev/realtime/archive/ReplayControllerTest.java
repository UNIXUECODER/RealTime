package dev.realtime.archive;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.auth.CurrentTenant;
import dev.realtime.tenancy.Channel;
import dev.realtime.tenancy.ChannelService;
import dev.realtime.web.GlobalErrorHandler;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-slice tests for {@link ReplayController}, validating the F-08 validation gate:
 * malformed stream IDs in {@code ?since=} must return 400 Bad Request with a structured
 * error envelope rather than surfacing as uncaught 500s.
 */
@WebFluxTest(ReplayController.class)
@Import(GlobalErrorHandler.class)
class ReplayControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReplayService replayService;

    @MockitoBean
    private ChannelService channelService;

    @MockitoBean
    private CurrentTenant currentTenant;

    @BeforeEach
    void setupAuth() {
        when(currentTenant.id()).thenReturn(Mono.just(42L));
        when(channelService.requireOwnedChannel(eq(42L), eq("chan-1")))
                .thenReturn(Mono.just(Channel.builder().id(1L).tenantId(42L).publicId("chan-1").build()));
    }

    @Test
    void validSinceZeroReturnsOk() {
        when(replayService.replay("chan-1", "0", 500))
                .thenReturn(Mono.just(List.of(new ReplayedEvent("1000-0", "{\"ok\":true}", "2026-09-19T12:00:00Z"))));

        webTestClient.get()
                .uri("/channels/chan-1/events?since=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("1000-0")
                .jsonPath("$[0].payload").isEqualTo("{\"ok\":true}");

        verify(replayService).replay("chan-1", "0", 500);
    }

    @Test
    void validSinceWithTimestampAndSequenceReturnsOk() {
        when(replayService.replay("chan-1", "1725900000000-0", 500))
                .thenReturn(Mono.just(List.of()));

        webTestClient.get()
                .uri("/channels/chan-1/events?since=1725900000000-0")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(replayService).replay("chan-1", "1725900000000-0", 500);
    }

    @Test
    void malformedNonNumericSinceReturns400() {
        webTestClient.get()
                .uri("/channels/chan-1/events?since=abc")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.error").isEqualTo("Bad Request")
                .jsonPath("$.message").isEqualTo("Invalid stream ID format: abc");

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void malformedSinceWithNegativeSequenceReturns400() {
        webTestClient.get()
                .uri("/channels/chan-1/events?since=1000--5")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("Invalid stream ID format: 1000--5");

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void malformedSinceWithExplicitPlusSignReturns400() {
        // Passing java.net.URI directly prevents WebTestClient from double-encoding '%2B' to '%252B'.
        // In query strings, %2B decodes to literal '+', arriving at the controller as '+1000'.
        webTestClient.get()
                .uri(java.net.URI.create("/channels/chan-1/events?since=%2B1000"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("Invalid stream ID format: +1000");

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void malformedSinceWithLeadingWhitespaceReturns400() {
        // Query param '+' or '%20' decodes to space, arriving at the controller as ' 1000'
        webTestClient.get()
                .uri(java.net.URI.create("/channels/chan-1/events?since=%201000"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("Invalid stream ID format:  1000");

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void emptySinceReturns400() {
        webTestClient.get()
                .uri("/channels/chan-1/events?since=")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void missingSinceParamReturns400() {
        webTestClient.get()
                .uri("/channels/chan-1/events")
                .exchange()
                .expectStatus().isBadRequest();

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void unownedChannelReturns404WithoutValidatingSince() {
        when(channelService.requireOwnedChannel(eq(42L), eq("other-chan")))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel")));

        webTestClient.get()
                .uri("/channels/other-chan/events?since=invalid-id-not-even-checked")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void limitBelowMinimumReturns400() {
        webTestClient.get()
                .uri("/channels/chan-1/events?since=0&limit=0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("limit must be between 1 and 5000 (received 0)");

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void limitAboveMaximumReturns400() {
        webTestClient.get()
                .uri("/channels/chan-1/events?since=0&limit=5001")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("limit must be between 1 and 5000 (received 5001)");

        verify(replayService, never()).replay(any(), any(), anyInt());
    }

    @Test
    void customValidLimitIsPassedThrough() {
        when(replayService.replay("chan-1", "0", 25)).thenReturn(Mono.just(List.of()));

        webTestClient.get()
                .uri("/channels/chan-1/events?since=0&limit=25")
                .exchange()
                .expectStatus().isOk();

        verify(replayService).replay("chan-1", "0", 25);
    }

    @Test
    void maximumLimitAtBoundaryReturnsOk() {
        when(replayService.replay("chan-1", "0", 5000)).thenReturn(Mono.just(List.of()));

        webTestClient.get()
                .uri("/channels/chan-1/events?since=0&limit=5000")
                .exchange()
                .expectStatus().isOk();

        verify(replayService).replay("chan-1", "0", 5000);
    }
}
