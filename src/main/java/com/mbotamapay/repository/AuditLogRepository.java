package com.mbotamapay.repository;

import com.mbotamapay.entity.AuditLog;
import com.mbotamapay.entity.enums.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Audit Log Repository
 * Provides queries for audit log retrieval and analysis
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Find all audit logs for a specific user
     */
    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Find audit logs by action type
     */
    Page<AuditLog> findByActionOrderByCreatedAtDesc(AuditAction action, Pageable pageable);

    /**
     * Find audit logs by entity
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    /**
     * Find audit logs within a date range
     */
    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * Find audit logs by IP address
     */
    List<AuditLog> findByIpAddressOrderByCreatedAtDesc(String ipAddress);

    /**
     * Find failed login attempts for a user
     */
    @Query("SELECT a FROM AuditLog a WHERE a.userId = :userId AND a.action = 'LOGIN_FAILED' " +
            "AND a.createdAt > :since ORDER BY a.createdAt DESC")
    List<AuditLog> findFailedLoginAttempts(Long userId, LocalDateTime since);

    /**
     * Count failed login attempts from an IP
     */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.ipAddress = :ip AND a.action = 'LOGIN_FAILED' " +
            "AND a.createdAt > :since")
    long countFailedLoginsByIp(String ip, LocalDateTime since);

    /**
     * Find suspicious activities
     */
    @Query("SELECT a FROM AuditLog a WHERE a.action IN ('SUSPICIOUS_ACTIVITY_DETECTED', 'RATE_LIMIT_EXCEEDED', 'INVALID_TOKEN_ATTEMPT') "
            +
            "AND a.createdAt > :since ORDER BY a.createdAt DESC")
    List<AuditLog> findSuspiciousActivities(LocalDateTime since);

    /**
     * Delete old audit logs (for GDPR compliance or storage management)
     */
    void deleteByCreatedAtBefore(LocalDateTime before);
}
