package io.platform.redis.repository;

import io.platform.redis.domain.entity.RedisInstance;
import io.platform.redis.domain.enums.ResourceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RedisInstanceRepository extends JpaRepository<RedisInstance, String> {

    List<RedisInstance> findByTenantId(String tenantId);

    Optional<RedisInstance> findByTenantIdAndName(String tenantId, String name);

    /**
     * Find all instances where desired != actual (state drift).
     * This is the core query for the reconciliation loop.
     */
    @Query("SELECT r FROM RedisInstance r WHERE r.desiredStatus != r.actualStatus")
    List<RedisInstance> findDrifted();

    List<RedisInstance> findByActualStatus(ResourceStatus status);

    @Query("SELECT r FROM RedisInstance r WHERE r.actualStatus = 'READY'")
    List<RedisInstance> findHealthy();
}
