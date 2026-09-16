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

    @Test
    void validateRejectsNullFieldWithBadRequest() {
        // F-09: a stored rule with a null field previously NPE'd inside extract() on
        // the next webhook delivered to the channel — the same bug shape as the
        // unsupported-operator case above, just for a different field on the same record.
        assertThatThrownBy(() -> engine.validate(List.of(new FilterRule(null, "==", "payment.failed"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void validateRejectsBlankFieldWithBadRequest() {
        assertThatThrownBy(() -> engine.validate(List.of(new FilterRule("   ", "==", "payment.failed"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void validateRejectsNullValueWithBadRequest() {
        assertThatThrownBy(() -> engine.validate(List.of(new FilterRule("type", "==", null))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void validateRejectsNullOpWithBadRequest() {
        assertThatThrownBy(() -> engine.validate(List.of(new FilterRule("type", null, "payment.failed"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void validateRejectsNullRuleWithBadRequest() {
        assertThatThrownBy(() -> {
            List<FilterRule> rules = java.util.Collections.singletonList(null);
            engine.validate(rules);
        }).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void validateAcceptsNullRulesList() {
        engine.validate(null);
    }

    @Test
    void validateRejectsMalformedJsonPathWithBadRequest() {
        assertThatThrownBy(() -> engine.validate(List.of(new FilterRule("$[unclosed", "==", "val"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void validateAcceptsValidJsonPathWithWhitespace() {
        engine.validate(List.of(
                new FilterRule("  type  ", "==", "payment.failed"),
                new FilterRule("$.items[0].id", "==", "item-123")));
    }

    @Test
    void validateRejectsExceedingMaxRulesPerChannel() {
        List<FilterRule> rules = java.util.stream.IntStream.range(0, FilterEngine.MAX_RULES_PER_CHANNEL + 1)
                .mapToObj(i -> new FilterRule("field" + i, "==", "val"))
                .toList();

        assertThatThrownBy(() -> engine.validate(rules))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void extractNeverThrowsOnMalformedPath() {
        // Defense-in-depth: even if an invalid path bypassed validation, extract() must
        // never bubble an unhandled InvalidPathException to the ingest path.
        List<FilterRule> rules = List.of(new FilterRule("$[unclosed", "==", "val"));
        assertThat(engine.matches("{\"a\":1}", rules)).isFalse();
    }

    @Test
    void matchesNeverThrowsWhenRuleHasNullField() {
        // Defense-in-depth: even if a rule with null field bypassed validation,
        // matches() must safely evaluate to false without throwing NPE.
        List<FilterRule> rules = List.of(new FilterRule(null, "==", "val"));
        assertThat(engine.matches("{\"a\":1}", rules)).isFalse();
    }

    @Test
    void matchesNeverThrowsWhenRuleHasBlankField() {
        List<FilterRule> rules = List.of(new FilterRule("   ", "==", "val"));
        assertThat(engine.matches("{\"a\":1}", rules)).isFalse();
    }

    @Test
    void matchesNeverThrowsWhenRawBodyIsNull() {
        List<FilterRule> rules = List.of(new FilterRule("type", "==", "payment.failed"));
        assertThat(engine.matches(null, rules)).isFalse();
    }

    @Test
    void matchesNeverThrowsWhenRawBodyIsMalformedJson() {
        List<FilterRule> rules = List.of(new FilterRule("type", "==", "payment.failed"));
        assertThat(engine.matches("{invalid-json", rules)).isFalse();
    }

    @Test
    void matchesNeverThrowsWhenRuleInListIsNull() {
        List<FilterRule> rules = java.util.Collections.singletonList(null);
        assertThat(engine.matches("{\"a\":1}", rules)).isFalse();
    }
}
