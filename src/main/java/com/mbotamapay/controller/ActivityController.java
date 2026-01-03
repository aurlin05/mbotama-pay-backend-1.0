package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.audit.ActivityLogResponse;
import com.mbotamapay.entity.AuditLog;
import com.mbotamapay.entity.User;
import com.mbotamapay.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Audit/Activity Controller
 * Provides endpoints for users to view their activity logs
 */
@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
@Slf4j
public class ActivityController {

    private final AuditService auditService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    /**
     * Get activity logs for the authenticated user
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ActivityLogResponse>>> getActivityLogs(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Getting activity logs for user: {}", user.getId());

        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLog> auditLogs = auditService.getAuditLogsForUser(user.getId(), pageable);

        Page<ActivityLogResponse> response = auditLogs.map(this::convertToActivityLogResponse);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get suspicious activities for the authenticated user
     */
    @GetMapping("/suspicious")
    public ResponseEntity<ApiResponse<Object>> getSuspiciousActivities(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "24") int hours) {

        log.info("Getting suspicious activities for user: {} (last {} hours)", user.getId(), hours);

        var suspiciousLogs = auditService.getSuspiciousActivities(hours)
                .stream()
                .filter(log -> user.getId().equals(log.getUserId()))
                .map(this::convertToActivityLogResponse)
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("count", suspiciousLogs.size());
        response.put("activities", suspiciousLogs);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Convert AuditLog to ActivityLogResponse
     */
    private ActivityLogResponse convertToActivityLogResponse(AuditLog log) {
        String type = mapActionToType(log.getAction().name());
        String title = generateTitle(log.getAction().name(), log.getSuccess());
        String description = log.getDescription() != null ? log.getDescription() : "";
        String date = log.getCreatedAt().format(DATE_FORMATTER);
        String location = extractLocation(log.getIpAddress());
        String device = extractDevice(log.getUserAgent());

        return ActivityLogResponse.builder()
                .id(log.getId())
                .type(type)
                .title(title)
                .description(description)
                .date(date)
                .location(location)
                .device(device)
                .ipAddress(log.getIpAddress())
                .success(log.getSuccess())
                .createdAt(log.getCreatedAt())
                .build();
    }

    /**
     * Map AuditAction to frontend activity type
     */
    private String mapActionToType(String action) {
        return switch (action) {
            case "LOGIN_SUCCESS" -> "login";
            case "LOGIN_FAILED" -> "suspicious";
            case "LOGOUT" -> "login";
            case "PASSWORD_CHANGE" -> "password_change";
            case "PASSWORD_RESET_REQUEST", "PASSWORD_RESET_COMPLETE" -> "password_change";
            case "OTP_VERIFIED" -> "2fa";
            case "OTP_FAILED" -> "suspicious";
            case "SUSPICIOUS_ACTIVITY_DETECTED", "RATE_LIMIT_EXCEEDED", "INVALID_TOKEN_ATTEMPT" -> "suspicious";
            case "USER_PROFILE_UPDATED" -> "profile_update";
            default -> "other";
        };
    }

    /**
     * Generate user-friendly title
     */
    private String generateTitle(String action, Boolean success) {
        if (success == null) success = true;

        return switch (action) {
            case "LOGIN_SUCCESS" -> "Connexion réussie";
            case "LOGIN_FAILED" -> "Tentative de connexion échouée";
            case "LOGOUT" -> "Déconnexion";
            case "PASSWORD_CHANGE" -> "Mot de passe modifié";
            case "PASSWORD_RESET_REQUEST" -> "Demande de réinitialisation";
            case "PASSWORD_RESET_COMPLETE" -> "Mot de passe réinitialisé";
            case "OTP_SENT" -> "Code de vérification envoyé";
            case "OTP_VERIFIED" -> "Code 2FA vérifié";
            case "OTP_FAILED" -> "Échec de vérification 2FA";
            case "USER_PROFILE_UPDATED" -> "Profil mis à jour";
            case "SUSPICIOUS_ACTIVITY_DETECTED" -> "Activité suspecte détectée";
            case "RATE_LIMIT_EXCEEDED" -> "Trop de tentatives";
            case "INVALID_TOKEN_ATTEMPT" -> "Tentative d'accès invalide";
            default -> success ? "Action réussie" : "Action échouée";
        };
    }

    /**
     * Extract location from IP address (placeholder - could integrate with GeoIP)
     */
    private String extractLocation(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return null;
        }
        
        // TODO: Integrate with GeoIP service for real location
        // For now, return null to indicate location not available
        return null;
    }

    /**
     * Extract device info from User-Agent
     */
    private String extractDevice(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "Appareil inconnu";
        }

        // Simple device extraction
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        } else if (userAgent.contains("iPad")) {
            return "iPad";
        } else if (userAgent.contains("Android")) {
            return "Android";
        } else if (userAgent.contains("Windows")) {
            return "Windows";
        } else if (userAgent.contains("Macintosh")) {
            return "MacOS";
        } else if (userAgent.contains("Linux")) {
            return "Linux";
        } else if (userAgent.contains("Chrome")) {
            return "Chrome";
        } else if (userAgent.contains("Safari")) {
            return "Safari";
        } else if (userAgent.contains("Firefox")) {
            return "Firefox";
        }

        return "Autre";
    }
}
