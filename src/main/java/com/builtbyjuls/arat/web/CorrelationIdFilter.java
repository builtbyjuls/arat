package com.builtbyjuls.arat.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE = "arat.correlationId";
    public static final String MDC_KEY = "correlationId";

    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var correlationId = correlationId(request.getHeader(HEADER_NAME));
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        MDC.put(MDC_KEY, correlationId);
        var startedAt = System.nanoTime();
        var status = response.getStatus();

        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } catch (IOException | ServletException | RuntimeException exception) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            throw exception;
        } finally {
            try {
                LOGGER.atInfo()
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", safePath(request))
                        .addKeyValue("status", status)
                        .addKeyValue("elapsedMs", (System.nanoTime() - startedAt) / 1_000_000)
                        .log("HTTP request completed");
            } finally {
                MDC.remove(MDC_KEY);
            }
        }
    }

    private String correlationId(String inboundValue) {
        if (inboundValue != null && SAFE_CORRELATION_ID.matcher(inboundValue).matches()) {
            return inboundValue;
        }
        return UUID.randomUUID().toString();
    }

    private String safePath(HttpServletRequest request) {
        var matchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matchingPattern instanceof String pattern) {
            return pattern;
        }
        return "/unmatched";
    }
}
