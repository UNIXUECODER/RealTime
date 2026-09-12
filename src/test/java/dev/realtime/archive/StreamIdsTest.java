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
}
