package dev.realtime.filter;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.springframework.stereotype.Component;

/**
 * Evaluates a channel's filter rules (JSONPath-based, AND-combined) against a raw
 * webhook payload. An event passes only if it matches every rule — an empty rule list
 * always passes (no filter configured means accept everything), per spec §5.
 */
@Component
public class FilterEngine {

    public boolean matches(String rawBody, List<FilterRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        return rules.stream().allMatch(rule -> matches(rawBody, rule));
    }

    private boolean matches(String rawBody, FilterRule rule) {
        Object actual = extract(rawBody, rule.field());
        String actualAsString = actual == null ? null : String.valueOf(actual);

        return switch (rule.op()) {
            case "==" -> rule.value().equals(actualAsString);
            case "!=" -> !rule.value().equals(actualAsString);
            default -> throw new IllegalArgumentException("Unsupported filter operator: " + rule.op());
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
