package com.mbotamapay.dto.auth;

import com.mbotamapay.entity.enums.KycLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for successful authentication
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
    private UserInfo user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String phoneNumber;
        private String countryCode;
        private String firstName;
        private String lastName;
        private String fullName;
        private String email;
        private KycLevel kycLevel;
        private String kycLevelDisplayName;
        private Long transactionLimit;
        private Boolean phoneVerified;
        private Boolean emailVerified;
        private String profilePictureUrl;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime lastLoginAt;
    }
}
