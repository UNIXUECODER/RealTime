package dev.realtime.tenancy;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code publicId} (a UUID) is what appears in every URL — {@code /webhook/{id}},
 * {@code /ws/{id}}, {@code /channels/{id}/...} — never the raw auto-increment
 * {@code id}. Same reasoning as the migration comment: sequential PKs shouldn't leave
 * the database.
 */
@Entity
@Table(name = "channels", uniqueConstraints = @UniqueConstraint(columnNames = "public_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "public_id", nullable = false)
    private String publicId;

    @Column(nullable = false)
    private String name;

    @Column(name = "retention_days", nullable = false)
    private Integer retentionDays;

    @Column(name = "rate_limit_per_sec")
    private Integer rateLimitPerSec;

    @Column(name = "dedup_field")
    private String dedupField;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
