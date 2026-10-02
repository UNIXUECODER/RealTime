package dev.realtime.live;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;

import dev.realtime.archive.ArchiveWriter;
import dev.realtime.archive.ArchivedEventRepository;
import dev.realtime.archive.RetentionTrimmer;
import dev.realtime.auth.ChannelAccessService;
import dev.realtime.tenancy.ApiKeyRepository;
import dev.realtime.tenancy.ChannelRepository;
import dev.realtime.tenancy.TenantRepository;
import dev.realtime.tenancy.UserRepository;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end WebSocket integration test against the real running Netty server (spec §5, M7 exit criteria):
 * Uses a real {@link ReactorNettyWebSocketClient} communicating over actual TCP/WebSocket wire
 * to verify:
 * 1. No filter receives every emitted event.
 * 2. Single filter receives only matching events.
 * 3. Simultaneous clients with different filters on the same channel: each receives only its matching subset.
 * 4. Malformed filter query parameter closes connection with RFC 6455 code 4400.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                        + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration"
        }
)
class ChannelWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReactiveRedisTemplate<String, String> redis;

    @MockitoBean
    private ChannelAccessService channelAccessService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TenantRepository tenantRepository;

    @MockitoBean
    private ChannelRepository channelRepository;

    @MockitoBean
    private ApiKeyRepository apiKeyRepository;

    @MockitoBean
    private ArchivedEventRepository archivedEventRepository;

    @MockitoBean
    private PlatformTransactionManager platformTransactionManager;

    @MockitoBean
    private RetentionTrimmer retentionTrimmer;

    @MockitoBean
    private ArchiveWriter archiveWriter;

    private ReactiveStreamOperations<String, String, String> streamOps;
    private Sinks.Many<MapRecord<String, String, String>> eventSink;
    private WebSocketClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        streamOps = mock(ReactiveStreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streamOps);
        when(channelAccessService.hasAccess(anyString(), anyString())).thenReturn(Mono.just(true));

        eventSink = Sinks.many().multicast().directBestEffort();
        when(streamOps.read(any(StreamReadOptions.class), any()))
                .thenAnswer(inv -> eventSink.asFlux());

        client = new ReactorNettyWebSocketClient();
    }

    private String wsUrl(String channelId, String filter) {
        String base = "ws://localhost:" + port + "/ws/" + channelId + "?token=test-token";
        if (filter != null) {
            base += "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);
        }
        return base;
    }

    private MapRecord<String, String, String> makeRecord(String channelId, String id, String payload) {
        return MapRecord.create(
                "stream:channel:" + channelId,
                Map.of("payload", payload, "received_at", "2026-09-24T12:00:00Z")
        ).withId(RecordId.of(id));
    }

    private void waitForSubscribers(int expectedCount) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (eventSink.currentSubscriberCount() < expectedCount) {
            if (System.currentTimeMillis() - start > 5000) {
                throw new AssertionError("Timed out waiting for " + expectedCount + " subscribers, current: "
                        + eventSink.currentSubscriberCount());
            }
            Thread.sleep(20);
        }
    }

    @Test
    void noFilterReceivesEveryEvent() throws Exception {
        String channelId = "chan-nofilter";
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        client.execute(URI.create(wsUrl(channelId, null)), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(msg -> {
                            received.add(msg);
                            latch.countDown();
                        })
                        .then()
        ).subscribe();

        waitForSubscribers(1);

        eventSink.tryEmitNext(makeRecord(channelId, "1000-0", "{\"type\":\"payment.failed\"}"));
        eventSink.tryEmitNext(makeRecord(channelId, "1000-1", "{\"type\":\"order.created\"}"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(2);
        assertThat(received.get(0)).contains("payment.failed");
        assertThat(received.get(1)).contains("order.created");
    }

    @Test
    void filterSetReceivesOnlyMatchingEvents() throws Exception {
        String channelId = "chan-filtered";
        String filter = "[{\"field\":\"type\",\"op\":\"==\",\"value\":\"payment.failed\"}]";
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        client.execute(URI.create(wsUrl(channelId, filter)), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(msg -> {
                            received.add(msg);
                            latch.countDown();
                        })
                        .then()
        ).subscribe();

        waitForSubscribers(1);

        // Emit non-matching event first, then matching event
        eventSink.tryEmitNext(makeRecord(channelId, "1000-0", "{\"type\":\"order.created\"}"));
        eventSink.tryEmitNext(makeRecord(channelId, "1000-1", "{\"type\":\"payment.failed\"}"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // Give a short grace period to ensure no unexpected frames arrive
        Thread.sleep(100);

        assertThat(received).hasSize(1);
        JsonNode node = objectMapper.readTree(received.get(0));
        assertThat(node.get("id").asText()).isEqualTo("1000-1");
        assertThat(node.get("payload").asText()).contains("payment.failed");
    }

    @Test
    void twoSimultaneousClientsWithDifferentFiltersReceiveIsolatedStreams() throws Exception {
        String channelId = "chan-dual";
        String filterA = "[{\"field\":\"type\",\"op\":\"==\",\"value\":\"payment.failed\"}]";
        String filterB = "[{\"field\":\"type\",\"op\":\"==\",\"value\":\"order.created\"}]";

        List<String> receivedA = new CopyOnWriteArrayList<>();
        List<String> receivedB = new CopyOnWriteArrayList<>();
        CountDownLatch latchA = new CountDownLatch(1);
        CountDownLatch latchB = new CountDownLatch(1);

        // Client A
        client.execute(URI.create(wsUrl(channelId, filterA)), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(msg -> {
                            receivedA.add(msg);
                            latchA.countDown();
                        })
                        .then()
        ).subscribe();

        // Client B
        client.execute(URI.create(wsUrl(channelId, filterB)), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(msg -> {
                            receivedB.add(msg);
                            latchB.countDown();
                        })
                        .then()
        ).subscribe();

        waitForSubscribers(2);

        // Mixed batch of 3 events:
        // 1: matches Client A only
        // 2: matches neither
        // 3: matches Client B only
        eventSink.tryEmitNext(makeRecord(channelId, "2000-0", "{\"type\":\"payment.failed\",\"amount\":100}"));
        eventSink.tryEmitNext(makeRecord(channelId, "2000-1", "{\"type\":\"user.login\",\"user_id\":\"u1\"}"));
        eventSink.tryEmitNext(makeRecord(channelId, "2000-2", "{\"type\":\"order.created\",\"total\":50}"));

        assertThat(latchA.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(latchB.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);

        // Client A got only Event 1
        assertThat(receivedA).hasSize(1);
        JsonNode nodeA = objectMapper.readTree(receivedA.get(0));
        assertThat(nodeA.get("id").asText()).isEqualTo("2000-0");
        assertThat(nodeA.get("payload").asText()).contains("payment.failed");

        // Client B got only Event 3
        assertThat(receivedB).hasSize(1);
        JsonNode nodeB = objectMapper.readTree(receivedB.get(0));
        assertThat(nodeB.get("id").asText()).isEqualTo("2000-2");
        assertThat(nodeB.get("payload").asText()).contains("order.created");
    }

    @Test
    void malformedFilterClosesWith4400() throws Exception {
        String channelId = "chan-badfilter";
        // Raw malformed JSON: [{invalid
        String badFilter = "[{invalid";
        AtomicReference<CloseStatus> clientCloseStatus = new AtomicReference<>();
        CountDownLatch closeLatch = new CountDownLatch(1);

        client.execute(URI.create(wsUrl(channelId, badFilter)), session -> {
            session.closeStatus()
                    .doOnNext(status -> {
                        clientCloseStatus.set(status);
                        closeLatch.countDown();
                    })
                    .subscribe();
            return session.receive().then();
        }).subscribe();

        boolean closed = closeLatch.await(5, TimeUnit.SECONDS);
        assertThat(closed).isTrue();
        assertThat(clientCloseStatus.get()).isNotNull();
        assertThat(clientCloseStatus.get().getCode()).isEqualTo(4400);
        assertThat(clientCloseStatus.get().getReason()).contains("filter query param must be a JSON array");
    }
}
