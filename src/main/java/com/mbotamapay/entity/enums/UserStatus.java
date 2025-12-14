package com.mbotamapay.entity.enums;

/**
 * User account status
 */
public enum UserStatus {
    PENDING_VERIFICATION, // Phone not verified yet
    ACTIVE, // Account active and usable
    SUSPENDED, // Temporarily suspended
    BLOCKED // Permanently blocked
}
