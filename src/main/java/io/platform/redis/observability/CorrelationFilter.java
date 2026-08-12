package io.platform.redis.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Request correlation filter.
 * Ensures every log line includes: requestId, tenantId for traceability.
 *
 * Combined with operationId/resourceId set by business logic,
 * support can trace any request through the entire system:
 *
 *   API Gateway → Platform API → Lifecycle Manager → Cloud Provider → Operator
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Extract or generate request ID
        String requestId = httpRequest.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        // Extract tenant ID
        String tenantId = httpRequest.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }

        // Set MDC for structured logging
        MDC.put("requestId", requestId);
        MDC.put("tenantId", tenantId);

        // Add response headers for correlation
        httpResponse.setHeader("X-Request-ID", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
