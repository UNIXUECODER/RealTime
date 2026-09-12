package dev.realtime.archive;

import java.time.Instant;

/**
 * Compares and interprets Redis Stream IDs ({@code "<millis>-<sequence>"}). String
 * comparison alone is wrong here — "999-0" would sort after "1000-0" lexically despite
 * being earlier — so ordering is done numerically on the parsed components instead.
 */
final class StreamIds {

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

    private static long[] parse(String streamId) {
        String[] segments = streamId.split("-", 2);
        long millis = Long.parseLong(segments[0]);
        long sequence = segments.length > 1 ? Long.parseLong(segments[1]) : 0L;
        return new long[]{millis, sequence};
    }
}
