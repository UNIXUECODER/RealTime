package dev.realtime.archive;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Compares and interprets Redis Stream IDs ({@code "<millis>-<sequence>"}). String
 * comparison alone is wrong here — "999-0" would sort after "1000-0" lexically despite
 * being earlier — so ordering is done numerically on the parsed components instead.
 *
 * <p>Public since M6c (F-08): {@link #isValid} is the single validity gate both {@link
 * ReplayController} (REST {@code since}) and {@link dev.realtime.live.ChannelWebSocketHandler}
 * (WS {@code last_id}) check a client-supplied ID against before it ever reaches {@link
 * #parse} — previously neither did, so a malformed ID reached {@code Long.parseLong}
 * uncaught: an opaque 500 on the REST path, an unhandled connection failure on the WS path.
 */
public final class StreamIds {

    private static final Pattern CANONICAL_PATTERN = Pattern.compile("^\\d+(-\\d+)?$");

    /**
     * Upper bound on millisecond timestamp component (~294,276 AD).
     * Values beyond this exceed PostgreSQL's {@code TIMESTAMPTZ} storage range
     * (SQLState 22008: date/time field value out of range), causing unhandled 500s
     * on the cold replay archive path if passed uncaught.
     */
    private static final long MAX_TIMESTAMP_MILLIS = 9_223_372_000_000_000L;

    private StreamIds() {
    }

    static int compare(String a, String b) {
        long[] partsA = parse(a);
        long[] partsB = parse(b);
        int msCompare = Long.compare(partsA[0], partsB[0]);
        return msCompare != 0 ? msCompare : Long.compare(partsA[1], partsB[1]);
    }

    /** Approximates a stream ID's position in time via its millisecond component. */
    static Instant timestampOf(String streamId) {
        return Instant.ofEpochMilli(parse(streamId)[0]);
    }

    /**
     * True if {@code id} is a well-formed stream ID that {@link #parse} can actually
     * parse without throwing. Deliberately delegates to {@link #parse} itself rather than
     * a separately-maintained regex, so this can never accept something {@code parse}
     * then rejects (or vice versa) — the same "single source of truth" reasoning {@code
     * FilterEngine.SUPPORTED_OPS} uses to keep its own validate/evaluate pair in sync.
     */
    public static boolean isValid(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            parse(id);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Extracts the sequence component of a stream ID. */
    static long sequenceOf(String streamId) {
        return parse(streamId)[1];
    }

    private static long[] parse(String streamId) {
        if (!CANONICAL_PATTERN.matcher(streamId).matches()) {
            throw new NumberFormatException("Invalid stream ID format: " + streamId);
        }
        String[] segments = streamId.split("-", 2);
        long millis = Long.parseLong(segments[0]);
        if (millis > MAX_TIMESTAMP_MILLIS) {
            throw new NumberFormatException("Stream ID millisecond timestamp exceeds maximum supported range: " + millis);
        }
        long sequence = segments.length > 1 ? Long.parseLong(segments[1]) : 0L;
        return new long[]{millis, sequence};
    }
}
