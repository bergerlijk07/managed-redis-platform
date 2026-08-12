package io.platform.redis.repository;

import io.platform.redis.domain.entity.Operation;
import io.platform.redis.domain.enums.OperationStatus;
import io.platform.redis.domain.enums.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperationRepository extends JpaRepository<Operation, String> {

    List<Operation> findByResourceId(String resourceId);

    List<Operation> findByResourceIdAndStatus(String resourceId, OperationStatus status);

    List<Operation> findByStatus(OperationStatus status);

    Optional<Operation> findFirstByResourceIdAndTypeAndStatusIn(
        String resourceId, OperationType type, List<OperationStatus> statuses);

    List<Operation> findByTenantId(String tenantId);
}
