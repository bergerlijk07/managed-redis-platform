package io.platform.redis.exception;

import java.util.List;

public class PolicyViolationException extends RuntimeException {

    private final List<String> violations;

    public PolicyViolationException(List<String> violations) {
        super("Policy violation: " + String.join("; ", violations));
        this.violations = violations;
    }

    public List<String> getViolations() {
        return violations;
    }
}
