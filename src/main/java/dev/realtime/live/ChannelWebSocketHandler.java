package dev.realtime.live;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import dev.realtime.auth.ChannelAccessService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * M2 core: live delivery from a channel's Redis Stream, with gap-free resume.
 * M6a adds: JWT auth via a {@code ?token=} query param, since browsers can't set
 * custom headers on a WebSocket handshake — see {@link ChannelAccessService} for why
 * this is handled manually here rather than through Spring Security's normal
 * header-based mechanism.
 *
 * <p><b>Scope note (deliberate deviation from spec §3/§8):</b> this implementation gives
 * each WebSocket session its own independent {@code XREAD} loop, rather than one shared
 * reader per channel multicasting to every local session. That's less efficient at scale,
 * but XREAD's own semantics (an explicit ID is an exclusive lower bound) already give
 * gap-free catch-up-then-live with no separate replay/live code paths and no buffering or
 * splicing between them. The shared-reader optimization the spec describes stays the
 * long-term target — it's deferred until M10's load test shows it's actually needed,
 * rather than built speculatively now at real correctness risk.
 */
@Component
public class ChannelWebSocketHandler implements WebSocketHandler {

    // Finite, not BLOCK 0 (infinite) — gives the loop a periodic checkpoint to notice a
    // cancelled session, rather than one Lettuce command blocked indefinitely with no
    // natural point to observe cancellation.
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(15);

    private final ReactiveRedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final ChannelAccessService channelAccessService;

    public ChannelWebSocketHandler(
            ReactiveRedisTemplate<String, String> redis,
            ObjectMapper objectMapper,
            ChannelAccessService channelAccessService) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.channelAccessService = channelAccessService;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String channelId = channelIdFrom(session);
        String token = queryParam(session, "token");

        return channelAccessService.hasAccess(channelId, token)
                .flatMap(authorized -> authorized
                        ? stream(session, channelId)
                        : session.close(new CloseStatus(4401, "Unauthorized")));
    }

    private Mono<Void> stream(WebSocketSession session, String channelId) {
        String lastId = queryParam(session, "last_id");
        String streamKey = "stream:channel:" + channelId;

        Flux<WebSocketMessage> outbound = tail(streamKey, lastId)
                .map(this::toFrame)
                .map(session::textMessage);

        return session.send(outbound);
    }

    private String channelIdFrom(WebSocketSession session) {
        // Parsed from the raw path rather than relying on Spring's URI-template-variable
        // propagation into WebSocketSession, which is less directly documented than the
        // equivalent for @RestController — this is simple, verifiable string logic instead.
        String path = session.getHandshakeInfo().getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String queryParam(WebSocketSession session, String name) {
        return UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri())
                .build()
                .getQueryParams()
                .getFirst(name);
    }

    /**
     * Streams a channel from a resume point, live, forever (until the session closes).
     * If {@code lastId} is absent, starts from "$" — tail-only, no history, for a fresh
     * connection with nothing to resume.
     */
    private Flux<MapRecord<String, String, String>> tail(String streamKey, String lastId) {
        String startId = (lastId != null && !lastId.isBlank()) ? lastId : "$";
        return poll(streamKey, new AtomicReference<>(startId));
    }

    /**
     * Repeats a blocking XREAD indefinitely, threading the last-seen ID forward as the
     * cursor for the next call.
     *
     * <p>This is the highest-risk code in the project so far — deliberately built as
     * {@code concatWith(Flux.defer(...))} rather than plain recursion, since Reactor
     * trampolines this shape internally instead of growing the call stack per iteration.
     * Verify this for real (per M2's manual exit criteria below); don't take "it compiles"
     * as evidence it's correct.
     */
    private Flux<MapRecord<String, String, String>> poll(String streamKey, AtomicReference<String> cursor) {
        return Flux.defer(() -> redis.<String, String>opsForStream()
                        .read(StreamReadOptions.empty().block(BLOCK_TIMEOUT),
                                StreamOffset.create(streamKey, ReadOffset.from(cursor.get()))))
                .doOnNext(record -> cursor.set(record.getId().getValue()))
                .concatWith(Flux.defer(() -> poll(streamKey, cursor)));
    }

    private String toFrame(MapRecord<String, String, String> record) {
        try {
            Map<String, Object> frame = Map.of(
                    "id", record.getId().getValue(),
                    "payload", record.getValue().get("payload"),
                    "received_at", record.getValue().get("received_at"));
            return objectMapper.writeValueAsString(frame);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize stream record", e);
        }
    }
}

