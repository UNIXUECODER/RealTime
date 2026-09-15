package dev.realtime.tenancy;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "api_keys")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    /** Last 4 characters of the raw key, captured once at creation, for masked display
     * on the dashboard (e.g. "rtk_...a1b2") — see V4 migration for why this can't be
     * derived from keyHash after the fact. Never used for authentication. */
    @Column(name = "key_suffix", nullable = false, length = 4)
    private String keySuffix;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null means active. Revocation is a soft-delete, not a row deletion — keeps an audit trail. */
    @Column(name = "revoked_at")
    private Instant revokedAt;
}
