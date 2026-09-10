package dev.realtime.filter;

/**
 * One channel-level filter rule: {@code field op value}, evaluated against a JSON
 * webhook payload. {@code field} is a JSONPath expression (or a bare property name,
 * which is normalized to {@code $.<field>}). Supported ops for v1: {@code ==} and
 * {@code !=} — an AND-only rule set with no numeric range operators, matching the v1
 * scope decided in the architecture spec §5/§13.
 */
public record FilterRule(String field, String op, String value) {
}
