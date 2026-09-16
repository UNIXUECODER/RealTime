package dev.realtime.filter;

import java.util.List;
import java.util.Set;

import com.jayway.jsonpath.InvalidPathException;
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

    /** Maximum allowed filter rules per channel to prevent Netty event-loop CPU starvation. */
    public static final int MAX_RULES_PER_CHANNEL = 50;

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
     * Rejects an invalid rule set at write time before it's ever stored.
     *
     * <p>Validates:
     * <ul>
     *   <li>Rule count does not exceed {@link #MAX_RULES_PER_CHANNEL}</li>
     *   <li>Non-null rule instances</li>
     *   <li>Non-blank, syntactically valid JSONPath in {@code field}</li>
     *   <li>Supported operator in {@code op}</li>
     *   <li>Non-null {@code value}</li>
     * </ul>
     */
    public void validate(List<FilterRule> rules) {
        if (rules == null) {
            return;
        }
        if (rules.size() > MAX_RULES_PER_CHANNEL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Maximum %d filter rules allowed per channel (received %d)"
                            .formatted(MAX_RULES_PER_CHANNEL, rules.size()));
        }
        for (FilterRule rule : rules) {
            if (rule == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter rule must not be null");
            }
            if (rule.field() == null || rule.field().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter rule 'field' must not be blank");
            }
            String path = normalizePath(rule.field());
            try {
                JsonPath.compile(path);
            } catch (InvalidPathException | IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid JSONPath expression in filter rule 'field': " + rule.field());
            }
            if (rule.op() == null || !SUPPORTED_OPS.contains(rule.op())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported filter operator '%s' — supported: %s".formatted(rule.op(), SUPPORTED_OPS));
            }
            if (rule.value() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter rule 'value' must not be null");
            }
        }
    }

    private boolean matches(String rawBody, FilterRule rule) {
        if (rule == null) {
            return false;
        }
        Object actual = extract(rawBody, rule.field());
        String actualAsString = actual == null ? null : String.valueOf(actual);

        if (rule.op() == null || !SUPPORTED_OPS.contains(rule.op())) {
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
        if (rawBody == null || field == null || field.isBlank()) {
            return null;
        }
        String path = normalizePath(field);
        try {
            return JsonPath.read(rawBody, path);
        } catch (Exception e) {
            // A missing field, malformed path, or evaluation failure is not an error — it
            // just means this rule doesn't match (or, for !=, that it trivially does),
            // rather than breaking ingest entirely over a filter rule referencing a field a particular event doesn't have.
            return null;
        }
    }

    private String normalizePath(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        String trimmed = field.trim();
        return trimmed.startsWith("$") ? trimmed : "$." + trimmed;
    }
}
