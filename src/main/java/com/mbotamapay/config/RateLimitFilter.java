package com.mbotamapay.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate Limit Filter
 * Intercepts HTTP requests and applies rate limiting based on endpoint type
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        Bucket bucket = selectBucket(path, clientIp);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Add rate limit headers
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            // Rate limit exceeded
            long waitTimeSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000;
            log.warn("Rate limit exceeded for IP: {} on path: {}. Wait time: {}s", clientIp, path, waitTimeSeconds);

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.addHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(waitTimeSeconds));
            response.getWriter().write(String.format(
                    "{\"success\":false,\"message\":\"Trop de requêtes. Veuillez réessayer dans %d secondes.\",\"code\":\"RATE_LIMIT_EXCEEDED\"}",
                    waitTimeSeconds));
        }
    }

    /**
     * Select the appropriate bucket based on the request path
     */
    private Bucket selectBucket(String path, String clientIp) {
        if (path.contains("/auth/login")) {
            return rateLimiter.getLoginBucket(clientIp);
        } else if (path.contains("/auth/verify-otp") || path.contains("/auth/resend-otp")) {
            return rateLimiter.getOtpBucket(clientIp);
        } else if (path.contains("/auth/register")) {
            return rateLimiter.getRegisterBucket(clientIp);
        } else {
            return rateLimiter.getGeneralBucket(clientIp);
        }
    }

    /**
     * Extract client IP, considering proxies and load balancers
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Take the first IP if there are multiple
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip rate limiting for static resources and health checks
        return path.startsWith("/actuator/health") ||
                path.startsWith("/swagger-ui") ||
                path.startsWith("/api-docs") ||
                path.endsWith(".css") ||
                path.endsWith(".js") ||
                path.endsWith(".ico");
    }
}
