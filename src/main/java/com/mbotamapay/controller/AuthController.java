package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.auth.AuthResponse;
import com.mbotamapay.dto.auth.OtpVerifyRequest;
import com.mbotamapay.dto.auth.PhoneAuthRequest;
import com.mbotamapay.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication Controller
 * Handles phone-based registration and login with OTP verification
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "APIs for user authentication")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register with phone number", description = "Sends OTP to the provided phone number for registration")
    public ResponseEntity<ApiResponse<String>> register(@Valid @RequestBody PhoneAuthRequest request) {
        authService.initiateRegistration(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Code OTP envoyé au " + request.getCountryCode() + request.getPhoneNumber(),
                "OTP_SENT"));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with phone number", description = "Sends OTP to the provided phone number for login")
    public ResponseEntity<ApiResponse<String>> login(@Valid @RequestBody PhoneAuthRequest request) {
        authService.initiateLogin(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Code OTP envoyé au " + request.getCountryCode() + request.getPhoneNumber(),
                "OTP_SENT"));
    }

    @PostMapping("/verify-otp")
    @Operation(summary = "Verify OTP code", description = "Verifies the OTP and returns JWT tokens")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        AuthResponse response = authService.verifyOtp(request);
        return ResponseEntity.ok(ApiResponse.success("Connexion réussie", response));
    }

    @PostMapping("/resend-otp")
    @Operation(summary = "Resend OTP code", description = "Resends OTP to the phone number")
    public ResponseEntity<ApiResponse<String>> resendOtp(@Valid @RequestBody PhoneAuthRequest request) {
        authService.resendOtp(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Nouveau code OTP envoyé",
                "OTP_RESENT"));
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Refresh access token", description = "Returns new access token using refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(@RequestHeader("Authorization") String refreshToken) {
        AuthResponse response = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(ApiResponse.success("Token rafraîchi", response));
    }
}
