package dev.realtime.tenancy;

import dev.realtime.ingest.IngestOutcome;

/**
 * Unlike the public webhook path (which always returns a bare 202 regardless of
 * outcome — spec §6, deliberately opaque to a third-party sender), this endpoint is a
 * dashboard testing tool, so it tells the truth: FILTERED and DUPLICATE are shown to
 * the person who just clicked Send, not hidden from them.
 */
public record TestEventResponse(IngestOutcome outcome) {
}
