package dev.realtime.filter;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import dev.realtime.auth.CurrentTenant;
import dev.realtime.tenancy.Channel;
import dev.realtime.tenancy.ChannelService;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ChannelFilterController} enforces rule validation at the HTTP
 * boundary, ensuring malformed or null fields/values/operators are rejected with 400 Bad
 * Request before reaching storage or the ingest path (F-09).
 */
@WebFluxTest(ChannelFilterController.class)
@Import(FilterEngine.class)
class ChannelFilterControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ChannelFilterStore store;

    @MockitoBean
    private ChannelService channelService;

    @MockitoBean
    private CurrentTenant currentTenant;

    @BeforeEach
    void setupAuth() {
        when(currentTenant.id()).thenReturn(Mono.just(10L));
        when(channelService.requireOwnedChannel(eq(10L), eq("ch1")))
                .thenReturn(Mono.just(Channel.builder().id(1L).tenantId(10L).publicId("ch1").build()));
    }

    @Test
    void setValidRulesReturnsOk() {
        String body = """
                [
                    {"field": "type", "op": "==", "value": "payment.failed"},
                    {"field": "amount", "op": "!=", "value": "0"}
                ]
                """;

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        verify(store).setRules(eq("ch1"), eq(List.of(
                new FilterRule("type", "==", "payment.failed"),
                new FilterRule("amount", "!=", "0"))));
    }

    @Test
    void setRulesWithNullFieldReturnsBadRequest() {
        String body = """
                [
                    {"field": null, "op": "==", "value": "payment.failed"}
                ]
                """;

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesWithBlankFieldReturnsBadRequest() {
        String body = """
                [
                    {"field": "   ", "op": "==", "value": "payment.failed"}
                ]
                """;

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesWithNullValueReturnsBadRequest() {
        String body = """
                [
                    {"field": "type", "op": "==", "value": null}
                ]
                """;

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesWithNullOpReturnsBadRequest() {
        String body = """
                [
                    {"field": "type", "op": null, "value": "payment.failed"}
                ]
                """;

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesWithUnsupportedOpReturnsBadRequest() {
        String body = """
                [
                    {"field": "amount", "op": ">", "value": "100"}
                ]
                """;

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesWithNullBodyReturnsOkAndStoresNoFilters() {
        // Regression test: FilterEngine.validate(null) treats a null rules list as
        // nothing to check (mirrors matches()'s own "null = match everything" stance),
        // but ChannelFilterStore.setRules used to reach List.copyOf(null) immediately
        // after — an uncaught NPE, i.e. a 500, on a request that looks identical to
        // PUT-ing an empty array from the outside. A client sending a literal `null`
        // body (Content-Type: application/json, body "null") reaches the controller
        // with `rules == null` — Spring/Jackson deserializes the JSON literal to a null
        // reference rather than rejecting the request, since the body itself isn't
        // missing.
        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("null")
                .exchange()
                .expectStatus().isOk();

        verify(store).setRules(eq("ch1"), eq(List.of()));
    }

    @Test
    void setRulesWithMalformedJsonPathReturnsBadRequest() {
        String body = """
                [
                    {"field": "$[unclosed", "op": "==", "value": "x"}
                ]
                """;

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesExceedingMaxCountReturnsBadRequest() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i <= FilterEngine.MAX_RULES_PER_CHANNEL; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"field\":\"f").append(i).append("\",\"op\":\"==\",\"value\":\"v\"}");
        }
        sb.append("]");

        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sb.toString())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getRulesReturnsConfiguredRules() {
        when(store.getRules("ch1")).thenReturn(List.of(
                new FilterRule("type", "==", "payment.failed")));

        webTestClient.get()
                .uri("/channels/ch1/filters")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].field").isEqualTo("type")
                .jsonPath("$[0].op").isEqualTo("==")
                .jsonPath("$[0].value").isEqualTo("payment.failed");
    }

    @Test
    void clearRulesReturnsOk() {
        webTestClient.delete()
                .uri("/channels/ch1/filters")
                .exchange()
                .expectStatus().isOk();

        verify(store).clear("ch1");
    }

    @Test
    void setRulesWithEmptyArrayReturnsOkAndStoresNoFilters() {
        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("[]")
                .exchange()
                .expectStatus().isOk();

        verify(store).setRules(eq("ch1"), eq(List.of()));
    }

    @Test
    void setRulesWithArrayContainingNullRuleReturnsBadRequest() {
        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("[null]")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesWithJsonObjectInsteadOfArrayReturnsBadRequest() {
        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"field\": \"type\", \"op\": \"==\", \"value\": \"test\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setRulesWithNonJsonBodyReturnsBadRequest() {
        webTestClient.put()
                .uri("/channels/ch1/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("not valid json")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
