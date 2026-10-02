package dev.realtime.filter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Evaluates a channel's or connection's filter rules (JSONPath-based, AND-combined)
 * against a webhook payload. An event passes only if it matches every rule — an empty
 * rule list always passes (no filter configured means accept everything), per spec §5.
 *
 * <p>M7a (F-17): Parses the JSON payload once per evaluation into a {@link DocumentContext}
 * and caches compiled {@link JsonPath} instances, preventing $O(N \times R)$ string
 * re-parsing and path re-compilation on Netty event-loop threads.
 */
@Component
public class FilterEngine {

    /** Maximum allowed filter rules per channel to prevent Netty event-loop CPU starvation. */
    public static final int MAX_RULES_PER_CHANNEL = 50;

    /** Maximum allowed characters in a filter rule 'field' (JSONPath). */
    public static final int MAX_FIELD_LENGTH = 200;

    /** Maximum allowed characters in a filter rule 'op'. */
    public static final int MAX_OP_LENGTH = 10;

    /**
     * The only operators v1 supports (spec §5/§13) — the single source of truth for
     * both {@link #validate} (write time) and {@link #matches} (evaluation time), so the two
     * can never drift out of sync.
     */
    private static final Set<String> SUPPORTED_OPS = Set.of("==", "!=");

    /** Path cache for precompiled JsonPath expressions to eliminate compilation overhead per rule. */
    private final Map<String, JsonPath> pathCache = new ConcurrentHashMap<>();

    /**
     * Parses a raw JSON string into a {@link DocumentContext} once. Returns {@code null}
     * if {@code rawBody} is null, blank, or syntactically invalid JSON.
     */
    public DocumentContext parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return JsonPath.parse(rawBody);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Evaluates rules against a raw JSON payload by parsing it once (F-17).
     */
    public boolean matches(String rawBody, List<FilterRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        DocumentContext document = parse(rawBody);
        if (document == null) {
            return false;
        }
        return matchesDocument(document, rules);
    }

    /**
     * Evaluates rules against an already-parsed {@link DocumentContext} (F-17).
     */
    public boolean matchesDocument(DocumentContext document, List<FilterRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        if (document == null) {
            return false;
        }
        return rules.stream().allMatch(rule -> matches(document, rule));
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
            if (rule.field().length() > MAX_FIELD_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Filter rule 'field' exceeds maximum length of " + MAX_FIELD_LENGTH);
            }
            String path = normalizePath(rule.field());
            try {
                JsonPath.compile(path);
            } catch (InvalidPathException | IllegalArgumentException e) {
                String displayField = rule.field().length() > 60
                        ? rule.field().substring(0, 57) + "..."
                        : rule.field();
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid JSONPath expression in filter rule 'field': " + displayField);
            }
            if (rule.op() == null || !SUPPORTED_OPS.contains(rule.op())) {
                String displayOp = rule.op() == null
                        ? "null"
                        : (rule.op().length() > MAX_OP_LENGTH ? rule.op().substring(0, MAX_OP_LENGTH) + "..." : rule.op());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported filter operator '%s' — supported: %s".formatted(displayOp, SUPPORTED_OPS));
            }
            if (rule.value() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter rule 'value' must not be null");
            }
        }
    }

    private boolean matches(DocumentContext document, FilterRule rule) {
        if (rule == null) {
            return false;
        }
        Object actual = extract(document, rule.field());
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

    private Object extract(DocumentContext document, String field) {
        if (document == null || field == null || field.isBlank()) {
            return null;
        }
        String path = normalizePath(field);
        try {
            JsonPath compiled = pathCache.computeIfAbsent(path, JsonPath::compile);
            return document.read(compiled);
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
