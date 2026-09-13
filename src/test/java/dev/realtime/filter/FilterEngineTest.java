package dev.realtime.filter;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterEngineTest {

    private final FilterEngine engine = new FilterEngine();

    @Test
    void noRulesAlwaysMatches() {
        assertThat(engine.matches("{\"type\":\"anything\"}", List.of())).isTrue();
    }

    @Test
    void equalityRuleMatchesWhenFieldEqualsValue() {
        boolean result = engine.matches(
                "{\"type\":\"payment.failed\"}",
                List.of(new FilterRule("type", "==", "payment.failed")));

        assertThat(result).isTrue();
    }

    @Test
    void equalityRuleFailsWhenFieldDiffersFromValue() {
        boolean result = engine.matches(
                "{\"type\":\"payment.succeeded\"}",
                List.of(new FilterRule("type", "==", "payment.failed")));

        assertThat(result).isFalse();
    }

    @Test
    void inequalityRuleMatchesWhenFieldDiffersFromValue() {
        boolean result = engine.matches(
                "{\"type\":\"payment.succeeded\"}",
                List.of(new FilterRule("type", "!=", "payment.failed")));

        assertThat(result).isTrue();
    }

    @Test
    void missingFieldIsTreatedAsNonMatchForEquality() {
        // A rule referencing a field this particular event doesn't have shouldn't
        // throw — it just means the rule doesn't match, same as any other mismatch.
        boolean result = engine.matches(
                "{\"other\":\"value\"}",
                List.of(new FilterRule("type", "==", "payment.failed")));

        assertThat(result).isFalse();
    }

    @Test
    void multipleRulesAreAndCombined() {
        String payload = "{\"type\":\"payment.failed\",\"amount\":\"500\"}";

        assertThat(engine.matches(payload, List.of(
                new FilterRule("type", "==", "payment.failed"),
                new FilterRule("amount", "==", "500")))).isTrue();

        assertThat(engine.matches(payload, List.of(
                new FilterRule("type", "==", "payment.failed"),
                new FilterRule("amount", "==", "999")))).isFalse();
    }

    @Test
    void bareFieldNameIsNormalizedToRootPath() {
        // "type" and "$.type" should behave identically.
        boolean result = engine.matches(
                "{\"type\":\"payment.failed\"}",
                List.of(new FilterRule("$.type", "==", "payment.failed")));

        assertThat(result).isTrue();
    }

    @Test
    void validateAcceptsSupportedOperators() {
        // Should not throw.
        engine.validate(List.of(
                new FilterRule("type", "==", "payment.failed"),
                new FilterRule("amount", "!=", "0")));
    }

    @Test
    void validateAcceptsEmptyRuleList() {
        engine.validate(List.of());
    }

    @Test
    void validateRejectsUnsupportedOperatorWithBadRequest() {
        // This is the write-time guard: without it, a rule like this was previously
        // accepted by PUT /channels/{id}/filters and only failed later, as an
        // unhandled 500 on the ingest path (IllegalArgumentException from matches()),
        // triggered by the next webhook delivered to that channel — not by whoever
        // actually misconfigured the rule.
        assertThatThrownBy(() -> engine.validate(List.of(new FilterRule("amount", ">", "100"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
