package dev.realtime.tenancy;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates M6a fixes to {@link ChannelService}: transactional channel+API key
 * creation, and {@code requireOwnedChannel}'s tenant isolation logic (which is
 * now reused by {@code ReplayController} and {@code ChannelFilterController} too).
 */
class ChannelServiceTest {

    private ChannelRepository channelRepository;
    private ApiKeyRepository apiKeyRepository;
    private ChannelService channelService;

    @BeforeEach
    void setUp() {
        channelRepository = mock(ChannelRepository.class);
        apiKeyRepository = mock(ApiKeyRepository.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        channelService = new ChannelService(channelRepository, apiKeyRepository, txManager);
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
}
