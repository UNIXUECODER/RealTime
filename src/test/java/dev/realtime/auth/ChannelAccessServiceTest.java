package dev.realtime.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.tenancy.Channel;
import dev.realtime.tenancy.ChannelService;

import io.jsonwebtoken.JwtException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Validates {@link ChannelAccessService}, added in M6a to bridge JWT auth into
 * WebSocket handshakes — browsers can't set custom headers on a WS upgrade, so
 * the token is passed as a {@code ?token=} query param and validated here
 * instead of by Spring Security's header-based mechanism.
 */
class ChannelAccessServiceTest {

    private JwtService jwtService;
    private ChannelService channelService;
    private ChannelAccessService accessService;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        channelService = mock(ChannelService.class);
        accessService = new ChannelAccessService(jwtService, channelService);
    }

    @Test
    void validTokenAndOwnedChannelGrantsAccess() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(1L, 10L);
        when(jwtService.validate("good-token")).thenReturn(principal);
        when(channelService.requireOwnedChannel(10L, "ch-uuid"))
                .thenReturn(Mono.just(Channel.builder().id(1L).tenantId(10L)
                        .publicId("ch-uuid").name("test").retentionDays(7).build()));

        StepVerifier.create(accessService.hasAccess("ch-uuid", "good-token"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void validTokenButUnownedChannelDeniesAccess() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(1L, 10L);
        when(jwtService.validate("good-token")).thenReturn(principal);
        // requireOwnedChannel returns 404 for channels belonging to other tenants.
        when(channelService.requireOwnedChannel(10L, "not-mine"))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));

        StepVerifier.create(accessService.hasAccess("not-mine", "good-token"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void invalidTokenDeniesAccess() {
        when(jwtService.validate("bad-token")).thenThrow(new JwtException("invalid"));

        StepVerifier.create(accessService.hasAccess("ch-uuid", "bad-token"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void nullTokenDeniesAccess() {
        StepVerifier.create(accessService.hasAccess("ch-uuid", null))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void blankTokenDeniesAccess() {
        StepVerifier.create(accessService.hasAccess("ch-uuid", "   "))
                .expectNext(false)
                .verifyComplete();
    }
}
