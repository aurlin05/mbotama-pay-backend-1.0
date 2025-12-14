package com.mbotamapay.service;

import com.mbotamapay.entity.AuditLog;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.AuditAction;
import com.mbotamapay.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Audit Service
 * Provides comprehensive audit logging for all sensitive operations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Log an action with basic information
     */
    @Async
    public void log(AuditAction action, String description) {
        log(action, null, null, null, null, description, true, null);
    }

    /**
     * Log an action for a specific user
     */
    @Async
    public void logForUser(Long userId, String username, AuditAction action, String description) {
        log(userId, username, action, null, null, null, null, description, true, null);
    }

    /**
     * Log an action with entity reference
     */
    @Async
    public void logWithEntity(AuditAction action, String entityType, Long entityId, String description) {
        log(action, entityType, entityId, null, null, description, true, null);
    }

    /**
     * Log an action with old and new values (for tracking changes)
     */
    @Async
    public void logWithValues(AuditAction action, String entityType, Long entityId,
            String oldValue, String newValue, String description) {
        log(action, entityType, entityId, oldValue, newValue, description, true, null);
    }

    /**
     * Log a failed action
     */
    @Async
    public void logFailure(AuditAction action, String description, String errorMessage) {
        log(action, null, null, null, null, description, false, errorMessage);
    }

    /**
     * Log a failed action for a specific user
     */
    @Async
    public void logFailureForUser(Long userId, String username, AuditAction action,
            String description, String errorMessage) {
        log(userId, username, action, null, null, null, null, description, false, errorMessage);
    }

    /**
     * Core logging method - extracts current user and request info
     */
    private void log(AuditAction action, String entityType, Long entityId,
            String oldValue, String newValue, String description,
            boolean success, String errorMessage) {
        // Get current user from security context
        Long userId = null;
        String username = null;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User user) {
            userId = user.getId();
            username = user.getPhoneNumber();
        }

        log(userId, username, action, entityType, entityId, oldValue, newValue,
                description, success, errorMessage);
    }

    /**
     * Full logging method with all parameters
     */
    private void log(Long userId, String username, AuditAction action,
            String entityType, Long entityId,
            String oldValue, String newValue, String description,
            boolean success, String errorMessage) {
        try {
            AuditLog.AuditLogBuilder builder = AuditLog.builder()
                    .userId(userId)
                    .username(username)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .oldValue(oldValue)
                    .newValue(newValue)
                    .description(description)
                    .success(success)
                    .errorMessage(errorMessage);

            // Extract request information if available
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                    .getRequestAttributes();

            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                builder.ipAddress(getClientIp(request))
                        .userAgent(request.getHeader("User-Agent"))
                        .requestUri(request.getRequestURI())
                        .requestMethod(request.getMethod());
            }

            AuditLog auditLog = builder.build();
            auditLogRepository.save(auditLog);

            log.debug("Audit log created: action={}, userId={}, success={}",
                    action, userId, success);
        } catch (Exception e) {
            // Never let audit logging failures affect the main flow
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /**
     * Get audit logs for a user with pagination
     */
    public Page<AuditLog> getAuditLogsForUser(Long userId, Pageable pageable) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Get audit logs by action type
     */
    public Page<AuditLog> getAuditLogsByAction(AuditAction action, Pageable pageable) {
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
    }

    /**
     * Get audit logs for an entity
     */
    public List<AuditLog> getAuditLogsForEntity(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    /**
     * Get audit logs within a date range
     */
    public Page<AuditLog> getAuditLogsByDateRange(LocalDateTime start, LocalDateTime end, Pageable pageable) {
        return auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);
    }

    /**
     * Get suspicious activities from the last N hours
     */
    public List<AuditLog> getSuspiciousActivities(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return auditLogRepository.findSuspiciousActivities(since);
    }

    /**
     * Count failed login attempts from an IP
     */
    public long countFailedLoginsFromIp(String ip, int minutes) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        return auditLogRepository.countFailedLoginsByIp(ip, since);
    }

    /**
     * Extract client IP from request
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }
}
