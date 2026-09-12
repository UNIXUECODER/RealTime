package dev.realtime.ingest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.tenancy.ApiKeyHasher;
import dev.realtime.tenancy.ApiKeyRepository;
import dev.realtime.tenancy.Channel;
import dev.realtime.tenancy.ChannelRepository;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Ingest path's own auth mechanism (spec §10) — a per-channel API key, checked as
 * ordinary application logic rather than through Spring Security, since it's a
 * fundamentally different shape from user auth (one secret per channel, not per user).
 *
 * <p>404 for an unknown channelId, 401 for a missing/wrong key on a real channel —
 * deliberately distinguishable, unlike {@code ChannelService.findOwned}'s tenant
 * isolation. There's no cross-tenant enumeration risk here the way there is for
 * channel CRUD: channel public IDs are already unguessable UUIDs, and the webhook
 * path was open to any channelId string for four milestones before this one anyway.
 */
@Service
public class WebhookAuthService {

    private final ChannelRepository channelRepository;
    private final ApiKeyRepository apiKeyRepository;

    public WebhookAuthService(ChannelRepository channelRepository, ApiKeyRepository apiKeyRepository) {
        this.channelRepository = channelRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    public Mono<Channel> authenticate(String channelPublicId, String providedApiKey) {
        return Mono.fromCallable(() -> channelRepository.findByPublicId(channelPublicId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel")))
                .flatMap(channel -> verifyApiKey(channel, providedApiKey));
    }

    private Mono<Channel> verifyApiKey(Channel channel, String providedApiKey) {
        if (providedApiKey == null || providedApiKey.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing API key"));
        }
        return Mono.fromCallable(() -> apiKeyRepository.findByChannelIdAndRevokedAtIsNull(channel.getId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active API key for this channel")))
                .flatMap(apiKey -> {
                    if (!ApiKeyHasher.hash(providedApiKey).equals(apiKey.getKeyHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
                    }
                    return Mono.just(channel);
                });
    }
}
