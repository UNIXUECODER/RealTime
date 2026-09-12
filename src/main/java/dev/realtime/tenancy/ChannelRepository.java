package dev.realtime.tenancy;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
    Optional<Channel> findByPublicId(String publicId);

    List<Channel> findByTenantId(Long tenantId);
}
