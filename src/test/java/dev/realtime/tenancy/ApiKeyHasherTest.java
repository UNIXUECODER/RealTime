package dev.realtime.tenancy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    @Test
    void generatedKeysHaveTheExpectedPrefix() {
        assertThat(ApiKeyHasher.generate()).startsWith("rtk_");
    }

    @Test
    void generatedKeysAreUnique() {
        assertThat(ApiKeyHasher.generate()).isNotEqualTo(ApiKeyHasher.generate());
    }

    @Test
    void hashingIsDeterministic() {
        assertThat(ApiKeyHasher.hash("some-key")).isEqualTo(ApiKeyHasher.hash("some-key"));
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        assertThat(ApiKeyHasher.hash("key-a")).isNotEqualTo(ApiKeyHasher.hash("key-b"));
    }
}
