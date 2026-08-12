package io.platform.redis.api.controller;

import io.platform.redis.api.dto.GetOperationResponse;
import io.platform.redis.repository.OperationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Operations API - allows customers to track async lifecycle operations.
 */
@RestController
@RequestMapping("/v1/operations")
public class OperationController {

    private final OperationRepository operationRepository;

    public OperationController(OperationRepository operationRepository) {
        this.operationRepository = operationRepository;
    }

    /**
     * GET /v1/operations/:id
     * Returns the current status and phase of an async operation.
     */
    @GetMapping("/{id}")
    public ResponseEntity<GetOperationResponse> get(@PathVariable String id) {
        return operationRepository.findById(id)
            .map(op -> ResponseEntity.ok(new GetOperationResponse(
                op.getId(),
                op.getResourceId(),
                op.getType().name(),
                op.getStatus().name(),
                op.getPhase().name(),
                op.getErrorMessage(),
                op.getCreatedAt(),
                op.getUpdatedAt()
            )))
            .orElse(ResponseEntity.notFound().build());
    }
}
