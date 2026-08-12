package io.platform.redis.domain.enums;

public enum ResourceStatus {
    REQUESTED,
    VALIDATING,
    PROVISIONING,
    CONFIGURING,
    READY,
    DEGRADED,
    UPGRADING,
    DELETING,
    DELETED,
    FAILED,
    ROLLING_BACK
}
