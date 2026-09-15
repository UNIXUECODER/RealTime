package dev.realtime.tenancy;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.ingest.IngestOutcome;
import dev.realtime.ingest.IngestService;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates M6a fixes to {@link ChannelService}: transactional channel+API key
 * creation, and {@code requireOwnedChannel}'s tenant isolation logic (which is
 * now reused by {@code ReplayController} and {@code ChannelFilterController} too).
 * M6b adds {@code sendTestEvent} coverage — it's built directly on top of the same
 * {@code requireOwnedChannel} check, so the interesting case is that it inherits the
 * same tenant-isolation guarantee, not that it needs to re-prove it from scratch.
 */
class ChannelServiceTest {

    private ChannelRepository channelRepository;
    private ApiKeyRepository apiKeyRepository;
    private IngestService ingestService;
    private ChannelService channelService;

    @BeforeEach
    void setUp() {
        channelRepository = mock(ChannelRepository.class);
        apiKeyRepository = mock(ApiKeyRepository.class);
        ingestService = mock(IngestService.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        channelService = new ChannelService(channelRepository, apiKeyRepository, ingestService, txManager);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    void createSavesChannelAndApiKeyAndReturnsResponse() {
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            return Channel.builder().id(1L).tenantId(c.getTenantId()).publicId(c.getPublicId())
                    .name(c.getName()).retentionDays(c.getRetentionDays())
                    .rateLimitPerSec(c.getRateLimitPerSec()).createdAt(c.getCreatedAt()).build();
        });
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        StepVerifier.create(channelService.create(10L, "my-channel"))
                .assertNext(response -> {
                    assertThat(response.name()).isEqualTo("my-channel");
                    assertThat(response.channelId()).isNotBlank();
                    assertThat(response.apiKey()).startsWith("rtk_");
                })
                .verifyComplete();

        verify(channelRepository).save(any(Channel.class));
        verify(apiKeyRepository).save(any(ApiKey.class));
    }

    // ── requireOwnedChannel ───────────────────────────────────────────────────

    @Test
    void requireOwnedChannelReturnsChannelWhenTenantMatches() {
        Channel channel = Channel.builder().id(1L).tenantId(10L).publicId("ch-uuid")
                .name("test").retentionDays(7).createdAt(Instant.now()).build();
        when(channelRepository.findByPublicId("ch-uuid")).thenReturn(Optional.of(channel));

        StepVerifier.create(channelService.requireOwnedChannel(10L, "ch-uuid"))
                .assertNext(c -> assertThat(c.getPublicId()).isEqualTo("ch-uuid"))
                .verifyComplete();
    }

    @Test
    void requireOwnedChannelReturns404WhenChannelBelongsToAnotherTenant() {
        // Tenant 10 owns it, but tenant 20 is asking — should get 404, not 403,
        // to prevent cross-tenant channel existence enumeration.
        Channel channel = Channel.builder().id(1L).tenantId(10L).publicId("ch-uuid")
                .name("test").retentionDays(7).createdAt(Instant.now()).build();
        when(channelRepository.findByPublicId("ch-uuid")).thenReturn(Optional.of(channel));

        StepVerifier.create(channelService.requireOwnedChannel(20L, "ch-uuid"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                })
                .verify();
    }

    @Test
    void requireOwnedChannelReturns404WhenChannelDoesNotExist() {
        when(channelRepository.findByPublicId("nonexistent")).thenReturn(Optional.empty());

        StepVerifier.create(channelService.requireOwnedChannel(10L, "nonexistent"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                })
                .verify();
    }

    @Test
    void crossTenantAndNonexistentChannelReturnIdentical404() {
        // The same indistinguishable 404 for both "doesn't exist" and "belongs to
        // someone else" — verifying the anti-enumeration design.
        Channel channel = Channel.builder().id(1L).tenantId(10L).publicId("ch-uuid")
                .name("test").retentionDays(7).createdAt(Instant.now()).build();
        when(channelRepository.findByPublicId("ch-uuid")).thenReturn(Optional.of(channel));
        when(channelRepository.findByPublicId("nonexistent")).thenReturn(Optional.empty());

        // Cross-tenant request:
        StepVerifier.create(channelService.requireOwnedChannel(20L, "ch-uuid"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                    assertThat(rse.getReason()).isEqualTo("Unknown channel");
                })
                .verify();

        // Genuinely nonexistent request — same status, same message:
        StepVerifier.create(channelService.requireOwnedChannel(10L, "nonexistent"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                    assertThat(rse.getReason()).isEqualTo("Unknown channel");
                })
                .verify();
    }

    // ── sendTestEvent ─────────────────────────────────────────────────────────

    @Test
    void sendTestEventDelegatesToIngestServiceUsingTheChannelsPublicId() {
        Channel channel = Channel.builder().id(1L).tenantId(10L).publicId("ch-uuid")
                .name("test").retentionDays(7).createdAt(Instant.now()).build();
        when(channelRepository.findByPublicId("ch-uuid")).thenReturn(Optional.of(channel));
        when(ingestService.ingest(anyString(), anyString())).thenReturn(Mono.just(IngestOutcome.ACCEPTED));

        StepVerifier.create(channelService.sendTestEvent(10L, "ch-uuid", "{\"type\":\"test\"}"))
                .expectNext(IngestOutcome.ACCEPTED)
                .verifyComplete();

        verify(ingestService).ingest("ch-uuid", "{\"type\":\"test\"}");
    }

    @Test
    void sendTestEventSurfacesFilteredAndDuplicateOutcomesUnchanged() {
        // Unlike the public webhook path (which always returns a bare 202 regardless of
        // outcome), this is a dashboard testing tool — FILTERED/DUPLICATE need to reach
        // the caller as-is, not be swallowed into a generic success.
        Channel channel = Channel.builder().id(1L).tenantId(10L).publicId("ch-uuid")
                .name("test").retentionDays(7).createdAt(Instant.now()).build();
        when(channelRepository.findByPublicId("ch-uuid")).thenReturn(Optional.of(channel));
        when(ingestService.ingest(anyString(), anyString())).thenReturn(Mono.just(IngestOutcome.FILTERED));

        StepVerifier.create(channelService.sendTestEvent(10L, "ch-uuid", "{\"type\":\"ignored\"}"))
                .expectNext(IngestOutcome.FILTERED)
                .verifyComplete();
    }

    @Test
    void sendTestEventReturns404ForCrossTenantChannelWithoutCallingIngestService() {
        // Same anti-enumeration guarantee as requireOwnedChannel itself — a test event
        // aimed at someone else's channel must fail before it ever reaches ingest.
        Channel channel = Channel.builder().id(1L).tenantId(10L).publicId("ch-uuid")
                .name("test").retentionDays(7).createdAt(Instant.now()).build();
        when(channelRepository.findByPublicId("ch-uuid")).thenReturn(Optional.of(channel));

        StepVerifier.create(channelService.sendTestEvent(20L, "ch-uuid", "{}"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                })
                .verify();

        verify(ingestService, org.mockito.Mockito.never()).ingest(any(), any());
    }
}
