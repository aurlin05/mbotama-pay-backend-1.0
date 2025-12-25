package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.user.UpdateProfileRequest;
import com.mbotamapay.dto.user.UserLimitsResponse;
import com.mbotamapay.dto.user.UserProfileResponse;
import com.mbotamapay.entity.User;
import com.mbotamapay.service.TransactionLimitsService;
import com.mbotamapay.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * User Controller for profile management
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "APIs for user profile management")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;
    private final TransactionLimitsService transactionLimitsService;

    @GetMapping("/me")
    @Operation(summary = "Get current user profile", description = "Returns the profile of the authenticated user")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getCurrentUser(
            @AuthenticationPrincipal User user) {
        UserProfileResponse profile = userService.getProfile(user.getId());
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PutMapping("/me")
    @Operation(summary = "Update current user profile", description = "Updates the profile of the authenticated user")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateCurrentUser(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserProfileResponse profile = userService.updateProfile(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("Profil mis à jour", profile));
    }

    @GetMapping("/me/transaction-limit")
    @Operation(summary = "Get transaction limit", description = "Returns the current transaction limit based on KYC level")
    public ResponseEntity<ApiResponse<TransactionLimitInfo>> getTransactionLimit(
            @AuthenticationPrincipal User user) {
        TransactionLimitInfo info = new TransactionLimitInfo(
                user.getKycLevel().name(),
                user.getKycLevel().getDisplayName(),
                user.getTransactionLimit());
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    @GetMapping("/me/limits")
    @Operation(
        summary = "Get detailed transaction limits", 
        description = "Returns comprehensive information about transaction limits including daily, monthly, and corridor-specific limits"
    )
    public ResponseEntity<ApiResponse<UserLimitsResponse>> getDetailedLimits(
            @AuthenticationPrincipal User user) {
        UserLimitsResponse limits = transactionLimitsService.getDetailedUserLimits(user);
        return ResponseEntity.ok(ApiResponse.success(
                "Limites de transaction récupérées avec succès", 
                limits
        ));
    }

    record TransactionLimitInfo(String kycLevel, String kycLevelDisplayName, Long transactionLimit) {
    }
}
