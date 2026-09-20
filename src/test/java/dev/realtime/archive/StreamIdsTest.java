package dev.realtime.archive;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamIdsTest {

    @Test
    void comparesByMillisecondFirst() {
        assertThat(StreamIds.compare("999-5", "1000-0")).isNegative();
        assertThat(StreamIds.compare("1000-0", "999-5")).isPositive();
    }

    @Test
    void comparesBySequenceWhenMillisecondsMatch() {
        assertThat(StreamIds.compare("1000-0", "1000-1")).isNegative();
        assertThat(StreamIds.compare("1000-5", "1000-2")).isPositive();
    }

    @Test
    void equalIdsCompareAsZero() {
        assertThat(StreamIds.compare("1000-0", "1000-0")).isZero();
    }

    @Test
    void missingSequenceDefaultsToZero() {
        assertThat(StreamIds.compare("1000", "1000-0")).isZero();
    }

    @Test
    void timestampOfExtractsMillisecondComponent() {
        assertThat(StreamIds.timestampOf("1725900000000-0"))
                .isEqualTo(Instant.ofEpochMilli(1725900000000L));
    }

    @Test
    void isValidAcceptsMillisOnly() {
        assertThat(StreamIds.isValid("1000")).isTrue();
    }

    @Test
    void isValidAcceptsMillisAndSequence() {
        assertThat(StreamIds.isValid("1000-0")).isTrue();
    }

    @Test
    void isValidRejectsNull() {
        assertThat(StreamIds.isValid(null)).isFalse();
    }

    @Test
    void isValidRejectsBlank() {
        assertThat(StreamIds.isValid("")).isFalse();
        assertThat(StreamIds.isValid("   ")).isFalse();
    }

    @Test
    void isValidRejectsNonNumeric() {
        assertThat(StreamIds.isValid("abc")).isFalse();
        assertThat(StreamIds.isValid("undefined")).isFalse();
    }

    @Test
    void isValidRejectsMalformedSequence() {
        assertThat(StreamIds.isValid("1000-abc")).isFalse();
    }

    @Test
    void isValidRejectsTrailingOrLeadingDash() {
        assertThat(StreamIds.isValid("1000-")).isFalse();
        assertThat(StreamIds.isValid("-1000")).isFalse();
    }

    @Test
    void isValidRejectsExtraSegments() {
        // split("-", 2) leaves "0-0" as the second segment, which Long.parseLong then
        // rejects — exactly the delegation-to-parse benefit isValid is built on: this
        // needs no separate rule of its own, it just falls out of reusing parse().
        assertThat(StreamIds.isValid("1000-0-0")).isFalse();
    }

    @Test
    void isValidRejectsNegativeSequenceAndLeadingPlus() {
        assertThat(StreamIds.isValid("1000--5")).isFalse();
        assertThat(StreamIds.isValid("+1000")).isFalse();
        assertThat(StreamIds.isValid("1000-+5")).isFalse();
    }

    @Test
    void isValidRejectsValueExceedingLongRange() {
        // A regex checking digit shape alone would accept this; parse()'s
        // Long.parseLong correctly rejects it, and isValid inherits that for free.
        assertThat(StreamIds.isValid("99999999999999999999")).isFalse();
    }

    @Test
    void isValidRejectsTimestampExceedingPostgresLimit() {
        // Fits in 64-bit signed Long (Long.MAX_VALUE), but exceeds Postgres TIMESTAMPTZ range (~294,276 AD).
        // If accepted, cold replay query would fail with SQLState 22008 and return an unhandled 500.
        assertThat(StreamIds.isValid("9223372036854775807-0")).isFalse();
        assertThat(StreamIds.isValid("9000000000000000000-0")).isFalse();
    }

    @Test
    void isValidAcceptsTimestampAtUpperLimit() {
        assertThat(StreamIds.isValid("9223372000000000-0")).isTrue();
    }
}
