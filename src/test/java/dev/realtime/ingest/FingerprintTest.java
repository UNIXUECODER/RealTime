package dev.realtime.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {

    @Test
    void sameChannelAndBodyProduceSameFingerprint() {
        String a = Fingerprint.of("channel-1", "{\"type\":\"test\"}");
        String b = Fingerprint.of("channel-1", "{\"type\":\"test\"}");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentBodiesProduceDifferentFingerprints() {
        String a = Fingerprint.of("channel-1", "{\"type\":\"test\"}");
        String b = Fingerprint.of("channel-1", "{\"type\":\"other\"}");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void sameBodyOnDifferentChannelsProducesDifferentFingerprints() {
        // Guards against a cross-channel dedup collision — an identical payload sent
        // to two different channels must not be treated as the same event.
        String a = Fingerprint.of("channel-1", "{\"type\":\"test\"}");
        String b = Fingerprint.of("channel-2", "{\"type\":\"test\"}");

        assertThat(a).isNotEqualTo(b);
    }
}
