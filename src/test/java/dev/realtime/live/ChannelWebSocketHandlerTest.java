package dev.realtime.live;

import java.net.URI;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
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
class ChannelWebSocketHandlerTest {

    private ReactiveRedisTemplate<String, String> redis;
    private ReactiveStreamOperations<String, String, String> streamOps;
    private ObjectMapper objectMapper;
    private ChannelAccessService channelAccessService;
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
        handler = new ChannelWebSocketHandler(redis, objectMapper, channelAccessService);

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
}
