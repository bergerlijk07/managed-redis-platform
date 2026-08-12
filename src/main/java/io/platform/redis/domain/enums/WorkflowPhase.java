package io.platform.redis.domain.enums;

public enum WorkflowPhase {
    VALIDATING,
    ALLOCATING,
    NETWORK_SETUP,
    STORAGE_SETUP,
    DEPLOYING,
    CONFIGURING,
    VALIDATING_HEALTH,
    READY,
    FAILED,
    DELETING,
    DELETE_NETWORK,
    DELETE_STORAGE,
    DELETE_COMPLETE
}
