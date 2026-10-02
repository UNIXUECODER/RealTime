package dev.realtime.live;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import dev.realtime.filter.FilterEngine;
import dev.realtime.filter.FilterRule;

/**
 * Parses and validates the optional {@code ?filter=} query param on a WebSocket
 * handshake into a connection-level filter rule list (M7a, spec §5).
 *
 * <p>Reuses the identical {@link FilterRule} shape and {@link FilterEngine#validate}
 * logic already proven for channel-level filters (spec §5's "same DSL, AND-only"
 * decision) — zero new grammar, zero new parser, and the exact "reject where written"
 * write-time-validation discipline this codebase applies everywhere else. Extracted
 * into its own small, independently unit-testable class rather than inlined into
 * {@link ChannelWebSocketHandler} — that handler's own Javadoc already flags it as
 * "the highest-risk code in the project," and the established pattern for
 * handler-adjacent logic here ({@link dev.realtime.archive.StreamIds}, {@link
 * dev.realtime.ingest.Fingerprint}, {@link dev.realtime.ingest.JsonValidator}) is a
 * standalone class with its own direct tests, not inline handler code.
 *
 * <p><b>Format:</b> a URL-encoded JSON array of {@link FilterRule} objects — e.g. the
 * raw (pre-encoding) value {@code [{"field":"type","op":"==","value":"payment.failed"}]}.
 * Decoded via {@link URLDecoder} against the {@code application/x-www-form-urlencoded}
 * convention that {@code encodeURIComponent} (or any correct client-side encoder)
 * already produces — the same pairing every browser and HTTP client assumes for a
 * query param value. This class does the decoding itself rather than relying on the
 * caller: {@code ChannelWebSocketHandler.queryParam()} extracts query values via
 * {@code UriComponentsBuilder.fromUri(uri).build()}, which — unlike Spring MVC/WebFlux's
 * {@code @RequestParam} binding — returns them still percent-encoded, exactly as they
 * appeared in the handshake URI.
 *
 * <p><b>"No filter" contract:</b> an absent or blank {@code filter} param, and a
 * literal JSON {@code null}, all resolve to an empty list — never {@code null} — so a
 * caller never needs a null-check on top of {@link FilterEngine#matches}'s existing
 * "empty list = match everything" behavior, and this can be composed directly into a
 * reactive chain without risking Reactor's "no null in {@code map()}" rule (see {@code
 * AuthService.login}'s own NPE lesson in the build log for exactly this hazard).
 * Treating blank the same as absent — rather than a validation error, unlike {@code
 * ReplayController}'s required {@code since} param — mirrors this exact handler's own
 * {@code last_id} precedent: {@code filter}, like {@code last_id}, is optional, so
 * there's nothing to reject about a client that simply never set it.
 */
@Component
public class ConnectionFilterResolver {

    private final ObjectMapper objectMapper;
    private final FilterEngine filterEngine;

    public ConnectionFilterResolver(ObjectMapper objectMapper, FilterEngine filterEngine) {
        this.objectMapper = objectMapper;
        this.filterEngine = filterEngine;
    }

    /**
     * @param rawFilterParam the raw (still percent-encoded) {@code filter} query param
     *     value, exactly as {@code ChannelWebSocketHandler.queryParam()} returns it —
     *     {@code null} if the param was absent from the handshake URI entirely.
     * @return the parsed, validated rule list; empty (never {@code null}) if there's no
     *     filter to apply.
     * @throws ResponseStatusException (400) if the param is present and non-blank but
     *     isn't validly percent-encoded, isn't valid JSON, isn't a JSON array of {@code
     *     FilterRule} objects, or fails {@link FilterEngine#validate} (unsupported
     *     operator, malformed JSONPath, null field/value, over the rule-count cap,
     *     etc.). The caller is expected to catch this and close the socket with 4400,
     *     mirroring the existing 4401 (auth) / 4400 (malformed {@code last_id})
     *     pattern, rather than let a REST-flavored exception escape a WS handshake
     *     unchanged.
     */
    public List<FilterRule> resolve(String rawFilterParam) {
        if (rawFilterParam == null || rawFilterParam.isBlank()) {
            return List.of();
        }

        String decoded;
        try {
            decoded = URLDecoder.decode(rawFilterParam, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Malformed URL encoding in filter query param", e);
        }

        List<FilterRule> rules;
        try {
            rules = objectMapper.readValue(decoded, new TypeReference<List<FilterRule>>() {
            });
        } catch (JacksonException e) {
            // Deliberately not echoing the raw param value back into the message —
            // it's arbitrary client input, and the generic message is enough to act on.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "filter query param must be a JSON array of {field, op, value} rules", e);
        }

        // A literal JSON `null` (?filter=null) deserializes to a Java null, not an
        // exception — same "null body = no rules" normalization ChannelFilterController
        // already applies at its own request-body boundary (M6c, F-09 round 3), kept
        // consistent here rather than treated as a new, different edge case.
        if (rules == null) {
            return List.of();
        }

        filterEngine.validate(rules);
        return rules;
    }
}
