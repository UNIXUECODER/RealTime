package dev.realtime.live;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;

import dev.realtime.auth.ChannelAccessService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelWebSocketHandler}, specifically verifying authentication
 * gates and the M6c (F-08) input validation gate: malformed {@code last_id} closes with
 * RFC 6455 application code 4400 and static reason phrase "Invalid last_id format"
 * instead of unhandled server-side errors.
 */
import dev.realtime.filter.FilterEngine;

class ChannelWebSocketHandlerTest {

    private ReactiveRedisTemplate<String, String> redis;
    private ReactiveStreamOperations<String, String, String> streamOps;
    private ObjectMapper objectMapper;
    private ChannelAccessService channelAccessService;
    private ConnectionFilterResolver connectionFilterResolver;
    private FilterEngine filterEngine;
    private ChannelWebSocketHandler handler;

    private WebSocketSession session;
    private HandshakeInfo handshakeInfo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(ReactiveRedisTemplate.class);
        streamOps = mock(ReactiveStreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streamOps);
        objectMapper = new ObjectMapper();
        channelAccessService = mock(ChannelAccessService.class);
        filterEngine = new FilterEngine();
        connectionFilterResolver = new ConnectionFilterResolver(objectMapper, filterEngine);
        handler = new ChannelWebSocketHandler(redis, objectMapper, channelAccessService, connectionFilterResolver, filterEngine);

        session = mock(WebSocketSession.class);
        handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        when(session.send(any())).thenReturn(Mono.empty());
    }

    @Test
    void unauthorizedConnectionClosesWith4401() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=bad-token");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "bad-token")).thenReturn(Mono.just(false));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4401);
        assertThat(statusCaptor.getValue().getReason()).isEqualTo("Unauthorized");
        verify(session, never()).send(any());
    }

    @Test
    void malformedNonNumericLastIdClosesWith4400() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&last_id=abc");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4400);
        assertThat(statusCaptor.getValue().getReason()).isEqualTo("Invalid last_id format");
        verify(session, never()).send(any());
    }

    @Test
    void malformedNegativeSequenceLastIdClosesWith4400() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&last_id=1000--5");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4400);
        assertThat(statusCaptor.getValue().getReason()).isEqualTo("Invalid last_id format");
        verify(session, never()).send(any());
    }

    @Test
    void lastIdExceedingPostgresTimestampLimitClosesWith4400() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&last_id=9223372036854775807-0");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4400);
        assertThat(statusCaptor.getValue().getReason()).isEqualTo("Invalid last_id format");
        verify(session, never()).send(any());
    }

    @Test
    void validLastIdStartsStreaming() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&last_id=1000-0");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));
        when(streamOps.read(any(), eq(StreamOffset.create("stream:channel:chan-1", ReadOffset.from("1000-0")))))
                .thenReturn(Flux.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(session, never()).close(any());
        verify(session).send(any());
    }

    @Test
    void missingLastIdStartsStreamingFromTail() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));
        when(streamOps.read(any(), eq(StreamOffset.create("stream:channel:chan-1", ReadOffset.from("$")))))
                .thenReturn(Flux.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(session, never()).close(any());
        verify(session).send(any());
    }

    @Test
    void malformedFilterQueryParamClosesWith4400() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&filter=%5B%7Binvalid");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4400);
        assertThat(statusCaptor.getValue().getReason())
                .isEqualTo("filter query param must be a JSON array of {field, op, value} rules");
        verify(session, never()).send(any());
    }

    @Test
    void unsupportedOperatorInFilterClosesWith4400() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&filter=%5B%7B%22field%22%3A%22amount%22%2C%22op%22%3A%22%3E%22%2C%22value%22%3A%22100%22%7D%5D");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4400);
        assertThat(statusCaptor.getValue().getReason())
                .contains("Unsupported filter operator '>'");
        verify(session, never()).send(any());
    }

    @Test
    void validFilterStartsStreaming() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&filter=%5B%7B%22field%22%3A%22type%22%2C%22op%22%3A%22%3D%3D%22%2C%22value%22%3A%22payment.failed%22%7D%5D");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));
        when(streamOps.read(any(), eq(StreamOffset.create("stream:channel:chan-1", ReadOffset.from("$")))))
                .thenReturn(Flux.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(session, never()).close(any());
        verify(session).send(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionFilterDropsNonMatchingEvents() {
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&filter=%5B%7B%22field%22%3A%22type%22%2C%22op%22%3A%22%3D%3D%22%2C%22value%22%3A%22payment.failed%22%7D%5D");
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));

        org.springframework.data.redis.connection.stream.RecordId id1 =
                org.springframework.data.redis.connection.stream.RecordId.of("1000-0");
        MapRecord<String, String, String> rec1 = MapRecord.create("stream:channel:chan-1",
                java.util.Map.of("payload", "{\"type\":\"payment.failed\"}", "received_at", "2026-09-24T12:00:00Z")).withId(id1);

        org.springframework.data.redis.connection.stream.RecordId id2 =
                org.springframework.data.redis.connection.stream.RecordId.of("1000-1");
        MapRecord<String, String, String> rec2 = MapRecord.create("stream:channel:chan-1",
                java.util.Map.of("payload", "{\"type\":\"payment.succeeded\"}", "received_at", "2026-09-24T12:00:01Z")).withId(id2);

        when(streamOps.read(any(StreamReadOptions.class), eq(StreamOffset.create("stream:channel:chan-1", ReadOffset.from("$")))))
                .thenReturn(Flux.just(rec1, rec2))
                .thenReturn(Flux.never());

        org.springframework.web.reactive.socket.WebSocketMessage mockMsg = mock(org.springframework.web.reactive.socket.WebSocketMessage.class);
        when(session.textMessage(any())).thenReturn(mockMsg);

        ArgumentCaptor<Flux<org.springframework.web.reactive.socket.WebSocketMessage>> outboundCaptor =
                ArgumentCaptor.forClass(Flux.class);
        when(session.send(outboundCaptor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        StepVerifier.create(outboundCaptor.getValue().take(1))
                .expectNext(mockMsg)
                .verifyComplete();

        ArgumentCaptor<String> frameCaptor = ArgumentCaptor.forClass(String.class);
        verify(session, org.mockito.Mockito.times(1)).textMessage(frameCaptor.capture());
        assertThat(frameCaptor.getValue()).contains("1000-0");
        assertThat(frameCaptor.getValue()).contains("payment.failed");
        assertThat(frameCaptor.getValue()).doesNotContain("1000-1");
        assertThat(frameCaptor.getValue()).doesNotContain("payment.succeeded");
    }

    @Test
    void safeCloseReasonTruncatesToAtMost123Utf8Bytes() {
        // Null / blank
        assertThat(ChannelWebSocketHandler.safeCloseReason(null)).isEqualTo("Invalid filter");
        assertThat(ChannelWebSocketHandler.safeCloseReason("")).isEqualTo("Invalid filter");
        assertThat(ChannelWebSocketHandler.safeCloseReason("   ")).isEqualTo("Invalid filter");

        // Pure ASCII under limit
        String ascii100 = "a".repeat(100);
        assertThat(ChannelWebSocketHandler.safeCloseReason(ascii100)).isEqualTo(ascii100);

        // Pure ASCII exactly at 123 bytes limit
        String ascii123 = "a".repeat(123);
        assertThat(ChannelWebSocketHandler.safeCloseReason(ascii123)).isEqualTo(ascii123);

        // Pure ASCII exceeding limit (150 chars -> 120 + "..." = 123 bytes)
        String ascii150 = "a".repeat(150);
        String truncatedAscii = ChannelWebSocketHandler.safeCloseReason(ascii150);
        assertThat(truncatedAscii).isEqualTo("a".repeat(120) + "...");
        assertThat(truncatedAscii.getBytes(StandardCharsets.UTF_8).length).isEqualTo(123);

        // Multi-byte CJK (3 bytes per character): 50 * 3 = 150 bytes -> 40 chars (120 bytes) + "..." = 123 bytes
        String cjk50 = "あ".repeat(50);
        String truncatedCjk = ChannelWebSocketHandler.safeCloseReason(cjk50);
        assertThat(truncatedCjk).isEqualTo("あ".repeat(40) + "...");
        assertThat(truncatedCjk.getBytes(StandardCharsets.UTF_8).length).isEqualTo(123);

        // 4-byte Emojis (surrogate pairs in Java): 35 emojis = 140 bytes -> 30 emojis (120 bytes) + "..." = 123 bytes
        String emoji35 = "🚀".repeat(35);
        String truncatedEmoji = ChannelWebSocketHandler.safeCloseReason(emoji35);
        assertThat(truncatedEmoji).isEqualTo("🚀".repeat(30) + "...");
        assertThat(truncatedEmoji.getBytes(StandardCharsets.UTF_8).length).isEqualTo(123);
    }

    @Test
    void unauthenticatedHandshakeWithMassiveMultibyteInvalidFilterClosesWithSafeByteLength4400() {
        // Simulates an attacker sending a 200+ char multi-byte invalid JSONPath in ?filter=
        String rawJson = "[{\"field\":\"$[invalid." + "あ".repeat(80) + "\",\"op\":\"==\",\"value\":\"val\"}]";
        String encodedFilter = java.net.URLEncoder.encode(rawJson, StandardCharsets.UTF_8);
        URI uri = URI.create("ws://localhost:8080/ws/channels/chan-1?token=valid-token&filter=" + encodedFilter);
        when(handshakeInfo.getUri()).thenReturn(uri);
        when(channelAccessService.hasAccess("chan-1", "valid-token")).thenReturn(Mono.just(true));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4400);

        String reason = statusCaptor.getValue().getReason();
        assertThat(reason).isNotNull();
        // Crucial RFC 6455 control frame assertion: UTF-8 byte length MUST be <= 123 bytes
        assertThat(reason.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(123);
        verify(session, never()).send(any());
    }
}
