package com.example.ApexPay.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every HTTP request a business correlation ID so a payment can be traced
 * end-to-end: HTTP entry -> outbox row -> relay -> RabbitMQ -> consumer.
 *
 * <p>Reads {@value #HTTP_HEADER} from the request (or generates a UUID), exposes it as
 * the {@value #MDC_KEY} MDC key (picked up automatically by ECS structured logging),
 * and echoes it back on the response so callers can quote it in support requests.
 *
 * <p>This is deliberately NOT a W3C traceparent: distributed tracing (Micrometer
 * Tracing + OTel bridge) is a complementary concern and rides its own headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** HTTP request/response header carrying the correlation ID. */
    public static final String HTTP_HEADER = "X-Correlation-Id";

    /** AMQP message header carrying the correlation ID across the broker hop. */
    public static final String AMQP_HEADER = "x-correlation-id";

    /** MDC key; included in every structured log line while set. */
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HTTP_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HTTP_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
