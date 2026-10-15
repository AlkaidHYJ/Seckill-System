package com.example.flashsale.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final String TRACE_ID = "traceId";
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String REQUEST_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = normalize(request.getHeader(REQUEST_HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        try {
            MDC.put(TRACE_ID, traceId);
            response.setHeader(TRACE_HEADER, traceId);
            filterChain.doFilter(request, response);
            log.info("HTTP {} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return null;
        }
        return value;
    }
}
