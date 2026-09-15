package dev.realtime.ingest;

import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;

/**
 * Extracted from {@code WebhookController} at M6b, once the dashboard's "send test
 * event" endpoint needed the exact same check — a single source of truth for what
 * counts as valid ingestable JSON, rather than two copies that could drift.
 */
public final class JsonValidator {

    private JsonValidator() {
    }

    public static boolean isValid(String payload) {
        try {
            // Reuses json-path's own parser rather than Jackson directly — after the
            // Jackson 3 restructuring, better to lean on a code path already being
            // exercised for filtering than on an uncertain corner of the new hierarchy.
            JsonPath.parse(payload);
            return true;
        } catch (InvalidJsonException e) {
            return false;
        }
    }
}
