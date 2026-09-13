package dev.realtime.filter;

import java.util.List;
import java.util.Set;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Evaluates a channel's filter rules (JSONPath-based, AND-combined) against a raw
 * webhook payload. An event passes only if it matches every rule — an empty rule list
 * always passes (no filter configured means accept everything), per spec §5.
 */
@Component
public class FilterEngine {

    /**
     * The only operators v1 supports (spec §5/§13) — the single source of truth for
     * both {@link #validate} (write time) and {@link #matches(String, FilterRule)}
     * (evaluation time), so the two can never drift out of sync.
     */
    private static final Set<String> SUPPORTED_OPS = Set.of("==", "!=");

    public boolean matches(String rawBody, List<FilterRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        return rules.stream().allMatch(rule -> matches(rawBody, rule));
    }

    /**
     * Rejects a rule set containing an unsupported operator before it's ever stored.
     *
     * <p>Without this, a bad {@code op} (a typo, or a client assuming a richer DSL than
     * v1 actually offers) was accepted silently by {@code PUT /channels/{id}/filters},
     * and only surfaced later — as an unhandled {@link IllegalArgumentException} the
     * next time <em>any</em> webhook happened to hit that channel's ingest path, which
     * {@code GlobalErrorHandler}'s generic handler turned into an opaque 500 to a third
     * party's webhook sender, not a 400 to whoever actually misconfigured the rule.
     */
    public void validate(List<FilterRule> rules) {
        for (FilterRule rule : rules) {
            if (!SUPPORTED_OPS.contains(rule.op())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported filter operator '%s' — supported: %s".formatted(rule.op(), SUPPORTED_OPS));
            }
        }
    }

    private boolean matches(String rawBody, FilterRule rule) {
        Object actual = extract(rawBody, rule.field());
        String actualAsString = actual == null ? null : String.valueOf(actual);

        if (!SUPPORTED_OPS.contains(rule.op())) {
            // Reachable only if a rule bypassed validate() (e.g. seeded directly into
            // ChannelFilterStore rather than via the PUT endpoint) — validate() is what
            // normally catches this at write time.
            throw new IllegalArgumentException("Unsupported filter operator: " + rule.op());
        }
        return switch (rule.op()) {
            case "==" -> rule.value().equals(actualAsString);
            case "!=" -> !rule.value().equals(actualAsString);
            default -> throw new IllegalStateException("Unreachable — checked against SUPPORTED_OPS above");
        };
    }

    private Object extract(String rawBody, String field) {
        String path = field.startsWith("$") ? field : "$." + field;
        try {
            return JsonPath.read(rawBody, path);
        } catch (PathNotFoundException e) {
            // A missing field is not an error — it just means this rule doesn't match
            // (or, for !=, that it trivially does), rather than breaking ingest entirely
            // over a filter rule referencing a field a particular event doesn't have.
            return null;
        }
    }
}
