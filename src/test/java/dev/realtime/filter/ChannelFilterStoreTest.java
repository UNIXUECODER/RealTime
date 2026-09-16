package dev.realtime.filter;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelFilterStoreTest {

    private final ChannelFilterStore store = new ChannelFilterStore();

    @Test
    void getRulesForUnknownChannelReturnsEmptyList() {
        assertThat(store.getRules("ch1")).isEmpty();
    }

    @Test
    void setRulesStoresAndReturnsRules() {
        List<FilterRule> rules = List.of(new FilterRule("type", "==", "payment.failed"));
        store.setRules("ch1", rules);

        assertThat(store.getRules("ch1")).isEqualTo(rules);
    }

    @Test
    void setRulesWithNullListStoresEmptyListWithoutNpe() {
        // F-09 / Store hardening: setting null rules should store an empty list
        // rather than throwing NullPointerException in List.copyOf(null).
        store.setRules("ch1", null);

        assertThat(store.getRules("ch1")).isEmpty();
    }

    @Test
    void clearRemovesRulesForChannel() {
        store.setRules("ch1", List.of(new FilterRule("type", "==", "payment.failed")));
        store.clear("ch1");

        assertThat(store.getRules("ch1")).isEmpty();
    }

    @Test
    void setRulesWithNullChannelIdIsNoOpWithoutNpe() {
        store.setRules(null, List.of(new FilterRule("type", "==", "payment.failed")));
        assertThat(store.getRules(null)).isEmpty();
    }

    @Test
    void getRulesWithNullChannelIdReturnsEmptyListWithoutNpe() {
        assertThat(store.getRules(null)).isEmpty();
    }

    @Test
    void clearWithNullChannelIdIsNoOpWithoutNpe() {
        store.clear(null);
    }

    @Test
    void setRulesWithNullElementsFiltersOutNullsWithoutNpe() {
        List<FilterRule> dirtyList = java.util.Arrays.asList(
                new FilterRule("type", "==", "payment.failed"),
                null,
                new FilterRule("amount", "!=", "0"));

        store.setRules("ch1", dirtyList);

        assertThat(store.getRules("ch1")).containsExactly(
                new FilterRule("type", "==", "payment.failed"),
                new FilterRule("amount", "!=", "0"));
    }
}
