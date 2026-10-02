package dev.realtime.live;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.filter.FilterEngine;
import dev.realtime.filter.FilterRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionFilterResolverTest {

    private final ConnectionFilterResolver resolver =
            new ConnectionFilterResolver(new ObjectMapper(), new FilterEngine());

    private static String encode(String rawJson) {
        return URLEncoder.encode(rawJson, StandardCharsets.UTF_8);
    }

    @Test
    void nullParamResolvesToEmptyList() {
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void blankParamResolvesToEmptyList() {
        // Mirrors the handler's own last_id precedent: filter is optional, like last_id,
        // so a client that never set it isn't rejected — unlike ReplayController's
        // required `since`, where a blank value IS a validation error.
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve("   ")).isEmpty();
    }

    @Test
    void literalJsonNullResolvesToEmptyList() {
        // ?filter=null — no encoding needed, "null" has no special characters.
        assertThat(resolver.resolve("null")).isEmpty();
    }

    @Test
    void emptyJsonArrayResolvesToEmptyList() {
        assertThat(resolver.resolve(encode("[]"))).isEmpty();
    }

    @Test
    void singleRuleFilterParsesCorrectly() {
        String json = "[{\"field\":\"type\",\"op\":\"==\",\"value\":\"payment.failed\"}]";

        List<FilterRule> rules = resolver.resolve(encode(json));

        assertThat(rules).containsExactly(new FilterRule("type", "==", "payment.failed"));
    }

    @Test
    void multipleRulesParseInOrder() {
        String json = "[{\"field\":\"type\",\"op\":\"==\",\"value\":\"payment.failed\"},"
                + "{\"field\":\"amount\",\"op\":\"!=\",\"value\":\"0\"}]";

        List<FilterRule> rules = resolver.resolve(encode(json));

        assertThat(rules).containsExactly(
                new FilterRule("type", "==", "payment.failed"),
                new FilterRule("amount", "!=", "0"));
    }

    @Test
    void literalPlusSignInValueSurvivesRoundTripWhenProperlyEncoded() {
        // URLEncoder.encode escapes a literal '+' to "%2B" (it's itself a reserved
        // character in application/x-www-form-urlencoded, used to represent space) —
        // exactly what a correct client-side encodeURIComponent-style encoder does.
        // Verifies decode() doesn't misinterpret it as an encoded space.
        String json = "[{\"field\":\"code\",\"op\":\"==\",\"value\":\"a+b\"}]";

        List<FilterRule> rules = resolver.resolve(encode(json));

        assertThat(rules).containsExactly(new FilterRule("code", "==", "a+b"));
    }

    @Test
    void malformedUrlEncodingRejectedWithBadRequest() {
        // A trailing '%' isn't a valid percent-encoded triplet.
        assertThatThrownBy(() -> resolver.resolve("%5B%"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void malformedJsonRejectedWithBadRequest() {
        assertThatThrownBy(() -> resolver.resolve(encode("[{invalid")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nonArrayJsonRejectedWithBadRequest() {
        // A single rule object, not wrapped in an array.
        String json = "{\"field\":\"type\",\"op\":\"==\",\"value\":\"payment.failed\"}";

        assertThatThrownBy(() -> resolver.resolve(encode(json)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void arrayOfWrongShapeRejectedWithBadRequest() {
        // Array elements that aren't rule objects at all.
        assertThatThrownBy(() -> resolver.resolve(encode("[\"not-a-rule\"]")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unsupportedOperatorRejectedWithBadRequest() {
        // Delegates to FilterEngine.validate() — same write-time guard already proven
        // for channel-level filters, reused here rather than re-implemented.
        String json = "[{\"field\":\"amount\",\"op\":\">\",\"value\":\"100\"}]";

        assertThatThrownBy(() -> resolver.resolve(encode(json)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nullFieldRejectedWithBadRequest() {
        String json = "[{\"field\":null,\"op\":\"==\",\"value\":\"x\"}]";

        assertThatThrownBy(() -> resolver.resolve(encode(json)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void malformedJsonPathFieldRejectedWithBadRequest() {
        String json = "[{\"field\":\"$[unclosed\",\"op\":\"==\",\"value\":\"x\"}]";

        assertThatThrownBy(() -> resolver.resolve(encode(json)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void exceedingMaxRulesPerChannelRejectedWithBadRequest() {
        String rules = IntStream.range(0, FilterEngine.MAX_RULES_PER_CHANNEL + 1)
                .mapToObj(i -> "{\"field\":\"f" + i + "\",\"op\":\"==\",\"value\":\"v\"}")
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();
        String json = "[" + rules + "]";

        assertThatThrownBy(() -> resolver.resolve(encode(json)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void bareFieldNameRuleParsesAndIsUsableDirectlyByFilterEngine() {
        // Confirms the resolved rules are the exact same FilterRule shape FilterEngine
        // already knows how to evaluate — no adapter/translation layer needed.
        String json = "[{\"field\":\"type\",\"op\":\"==\",\"value\":\"payment.failed\"}]";
        List<FilterRule> rules = resolver.resolve(encode(json));

        FilterEngine engine = new FilterEngine();
        assertThat(engine.matches("{\"type\":\"payment.failed\"}", rules)).isTrue();
        assertThat(engine.matches("{\"type\":\"payment.succeeded\"}", rules)).isFalse();
    }
}
