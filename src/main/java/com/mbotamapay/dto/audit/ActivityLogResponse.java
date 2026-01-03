package com.mbotamapay.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Activity Log Response DTO
 * Represents user activity for frontend display
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityLogResponse {

    /**
     * Log ID
     */
    private Long id;

    /**
     * Activity type (for icon/color mapping)
     * Values: login, password_change, location, 2fa, suspicious, profile_update, other
     */
    private String type;

    /**
     * Activity title
     */
    private String title;

    /**
     * Activity description
     */
    private String description;

    /**
     * Formatted date string
     */
    private String date;

    /**
     * Location (city, country) - can be null
     */
    private String location;

    /**
     * Device name
     */
    private String device;

    /**
     * IP Address (for detailed view)
     */
    private String ipAddress;

    /**
     * Success status
     */
    private Boolean success;

    /**
     * Raw timestamp (for sorting)
     */
    private LocalDateTime createdAt;
}
