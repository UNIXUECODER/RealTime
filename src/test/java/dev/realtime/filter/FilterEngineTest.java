package dev.realtime.filter;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
