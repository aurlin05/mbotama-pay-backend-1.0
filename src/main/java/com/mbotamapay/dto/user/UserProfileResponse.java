package com.mbotamapay.dto.user;

import com.mbotamapay.entity.enums.KycLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * User profile response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private Long id;
    private String phoneNumber;
    private String countryCode;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String address;
    private String city;
    private LocalDate dateOfBirth;
    private KycLevel kycLevel;
    private String kycLevelDisplayName;
    private Long transactionLimit;
    private Boolean phoneVerified;
    private Boolean emailVerified;
    private String profilePictureUrl;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}
