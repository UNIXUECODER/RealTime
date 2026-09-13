package dev.realtime.tenancy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Every method here takes a {@code tenantId} sourced from the caller's validated JWT
 * (see {@code ChannelController}), never from a client-supplied field — the core
 * enforcement mechanism spec §10 calls for. {@link #requireOwnedChannel} is where
 * that's actually checked: a channel belonging to a different tenant is treated
 * identically to one that doesn't exist at all (404 either way), so this can't be used
 * to even confirm another tenant's channel IDs exist. As of M6a, this same check is
 * reused by {@code ReplayController} and {@code ChannelFilterController} — not just
 * channel CRUD.
 */
@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final TransactionTemplate transactionTemplate;

    public ChannelService(
            ChannelRepository channelRepository,
            ApiKeyRepository apiKeyRepository,
            PlatformTransactionManager transactionManager) {
        this.channelRepository = channelRepository;
        this.apiKeyRepository = apiKeyRepository;
        // See AuthService for why this is programmatic rather than @Transactional —
        // same self-invocation-through-Mono.fromCallable reasoning applies here.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a channel and its API key together, in one transaction — without it, an
     * API-key-save failure after the channel save already committed would leave a
     * channel with no working key, unusable but still occupying a slot.
     */
    public Mono<CreateChannelResponse> create(Long tenantId, String name) {
        return Mono.fromCallable(() -> transactionTemplate.execute(status -> {
                    Channel channel = channelRepository.save(Channel.builder()
                            .tenantId(tenantId)
                            .publicId(UUID.randomUUID().toString())
                            .name(name)
                            .retentionDays(7)
                            .rateLimitPerSec(100)
                            .createdAt(Instant.now())
                            .build());

                    String rawApiKey = ApiKeyHasher.generate();
                    apiKeyRepository.save(ApiKey.builder()
                            .channelId(channel.getId())
                            .keyHash(ApiKeyHasher.hash(rawApiKey))
                            .createdAt(Instant.now())
                            .build());

                    return new CreateChannelResponse(channel.getPublicId(), channel.getName(), rawApiKey);
                }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<ChannelDto>> listForTenant(Long tenantId) {
        return Mono.fromCallable(() -> channelRepository.findByTenantId(tenantId).stream()
                        .map(this::toDto)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ChannelDto> get(Long tenantId, String publicId) {
        return requireOwnedChannel(tenantId, publicId).map(this::toDto);
    }

    public Mono<Void> delete(Long tenantId, String publicId) {
        // Cascades to the channel's api_keys row automatically as of the V3 migration —
        // no separate application-level cleanup needed.
        return requireOwnedChannel(tenantId, publicId)
                .flatMap(channel -> Mono.fromRunnable(() -> channelRepository.delete(channel))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    /**
     * Fetches a channel by its public ID, scoped to the given tenant. Returns 404 for
     * BOTH "doesn't exist" and "belongs to a different tenant" — deliberately
     * indistinguishable, so a client can't use this to even confirm another tenant's
     * channel IDs are real.
     */
    public Mono<Channel> requireOwnedChannel(Long tenantId, String publicId) {
        return Mono.fromCallable(() -> channelRepository.findByPublicId(publicId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty)
                .filter(channel -> channel.getTenantId().equals(tenantId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel")));
    }

    private ChannelDto toDto(Channel channel) {
        return new ChannelDto(channel.getPublicId(), channel.getName(), channel.getCreatedAt().toString());
    }
}

