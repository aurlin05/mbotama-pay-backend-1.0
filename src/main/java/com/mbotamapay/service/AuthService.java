package com.mbotamapay.service;

import com.mbotamapay.dto.auth.AuthResponse;
import com.mbotamapay.dto.auth.OtpVerifyRequest;
import com.mbotamapay.dto.auth.PhoneAuthRequest;
import com.mbotamapay.entity.OtpCode;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.UserStatus;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.UnauthorizedException;
import com.mbotamapay.repository.OtpRepository;
import com.mbotamapay.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Authentication Service
 * Handles phone-based auth with OTP verification
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final JwtService jwtService;
    private final SmsService smsService;
    private final EmailService emailService;

    @Value("${otp.expiration}")
    private int otpExpiration;

    @Value("${otp.length}")
    private int otpLength;

    @Value("${otp.max-attempts}")
    private int maxAttempts;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    /**
     * Initiate registration - create user if not exists, send OTP
     */
    @Transactional
    public void initiateRegistration(PhoneAuthRequest request) {
        String fullPhone = request.getCountryCode() + request.getPhoneNumber();

        // Check if user already exists and is active
        userRepository.findByPhoneNumber(fullPhone).ifPresent(user -> {
            if (user.getPhoneVerified()) {
                throw new BadRequestException("Ce numéro est déjà enregistré. Utilisez la connexion.");
            }
        });

        // Create user if not exists, or update existing unverified user with profile info
        User user = userRepository.findByPhoneNumber(fullPhone).orElse(null);
        
        if (user == null) {
            // Create new user with profile information
            user = User.builder()
                    .phoneNumber(fullPhone)
                    .countryCode(request.getCountryCode())
                    .firstName(request.getFirstName())
                    .lastName(request.getLastName())
                    .email(request.getEmail())
                    .build();
            userRepository.save(user);
            log.info("New user created for phone: {} with profile info", fullPhone);
        } else {
            // Update existing unverified user with new profile information
            if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
            if (request.getLastName() != null) user.setLastName(request.getLastName());
            if (request.getEmail() != null) user.setEmail(request.getEmail());
            userRepository.save(user);
            log.info("Updated profile for unverified user: {}", fullPhone);
        }

        // Generate and send OTP
        sendOtp(fullPhone);
    }

    /**
     * Initiate login - verify user exists, send OTP
     */
    @Transactional
    public void initiateLogin(PhoneAuthRequest request) {
        String fullPhone = request.getCountryCode() + request.getPhoneNumber();

        User user = userRepository.findByPhoneNumber(fullPhone)
                .orElseThrow(() -> new BadRequestException("Aucun compte trouvé. Veuillez vous inscrire."));

        if (!user.getPhoneVerified()) {
            throw new BadRequestException("Compte non vérifié. Veuillez terminer votre inscription.");
        }

        if (!user.isEnabled()) {
            throw new UnauthorizedException("Votre compte est désactivé. Contactez le support.");
        }

        // Send OTP
        sendOtp(fullPhone);
    }

    /**
     * Verify OTP and return JWT tokens
     */
    @Transactional
    public AuthResponse verifyOtp(OtpVerifyRequest request) {
        log.info("=== VERIFY OTP START ===");
        log.info("Phone number received: {}", request.getPhoneNumber());
        log.info("Code received: {}", request.getCode());

        log.info("Step 1: Looking for OTP in database...");
        OtpCode otpCode = otpRepository
                .findFirstByPhoneNumberAndVerifiedFalseOrderByCreatedAtDesc(request.getPhoneNumber())
                .orElseThrow(() -> {
                    log.error("No OTP found for phone: {}", request.getPhoneNumber());
                    return new BadRequestException("Aucun code OTP actif. Veuillez en demander un nouveau.");
                });
        log.info("Step 1 OK: OTP found with id={}, code={}", otpCode.getId(), otpCode.getCode());

        // Check expiration
        log.info("Step 2: Checking OTP expiration...");
        if (otpCode.isExpired()) {
            log.error("OTP expired at: {}", otpCode.getExpiresAt());
            throw new BadRequestException("Code OTP expiré. Veuillez en demander un nouveau.");
        }
        log.info("Step 2 OK: OTP not expired, expires at: {}", otpCode.getExpiresAt());

        // Check attempts
        log.info("Step 3: Checking attempts... Current: {}, Max: {}", otpCode.getAttempts(), maxAttempts);
        if (otpCode.getAttempts() >= maxAttempts) {
            throw new BadRequestException("Trop de tentatives. Veuillez demander un nouveau code.");
        }
        log.info("Step 3 OK: Attempts within limit");

        // Verify code
        log.info("Step 4: Verifying OTP code... Expected: {}, Received: {}", otpCode.getCode(), request.getCode());
        if (!otpCode.getCode().equals(request.getCode())) {
            otpCode.incrementAttempts();
            otpRepository.save(otpCode);
            log.error("OTP code mismatch!");
            throw new BadRequestException(
                    "Code OTP invalide. Tentatives restantes: " + (maxAttempts - otpCode.getAttempts()));
        }
        log.info("Step 4 OK: OTP code matches");

        // Mark OTP as verified
        log.info("Step 5: Marking OTP as verified...");
        otpCode.setVerified(true);
        otpRepository.save(otpCode);
        log.info("Step 5 OK: OTP marked as verified");

        // Get or update user
        log.info("Step 6: Finding user by phone: {}", request.getPhoneNumber());
        User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
                .orElseThrow(() -> {
                    log.error("User not found for phone: {}", request.getPhoneNumber());
                    return new BadRequestException("Utilisateur non trouvé");
                });
        log.info("Step 6 OK: User found with id={}, kycLevel={}, status={}", user.getId(), user.getKycLevel(),
                user.getStatus());

        log.info("Step 7: Updating user verification status...");
        boolean isFirstVerification = !user.getPhoneVerified();
        if (isFirstVerification) {
            user.setPhoneVerified(true);
            user.setStatus(UserStatus.ACTIVE);
            log.info("Step 7: User phone verified set to true, status set to ACTIVE");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("Step 7 OK: User updated");

        // Send welcome SMS for new registrations (consistent with phone-based auth)
        if (isFirstVerification) {
            smsService.sendWelcome(user.getPhoneNumber(), user.getFirstName());
            log.info("Step 7.1: Welcome SMS queued for user: {}", user.getPhoneNumber());
        }

        // Generate tokens
        log.info("Step 8: Generating JWT access token...");
        String accessToken = jwtService.generateToken(user);
        log.info("Step 8 OK: Access token generated (length={})", accessToken.length());

        log.info("Step 9: Generating JWT refresh token...");
        String refreshToken = jwtService.generateRefreshToken(user);
        log.info("Step 9 OK: Refresh token generated (length={})", refreshToken.length());

        log.info("Step 10: Building AuthResponse...");
        log.info("User {} authenticated successfully", user.getPhoneNumber());

        AuthResponse response = AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtExpiration / 1000)
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId())
                        .phoneNumber(user.getPhoneNumber())
                        .countryCode(user.getCountryCode())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .kycLevel(user.getKycLevel())
                        .kycLevelDisplayName(user.getKycLevel().getDisplayName())
                        .transactionLimit(user.getTransactionLimit())
                        .phoneVerified(user.getPhoneVerified())
                        .emailVerified(user.getEmailVerified())
                        .profilePictureUrl(user.getProfilePictureUrl())
                        .createdAt(user.getCreatedAt())
                        .lastLoginAt(user.getLastLoginAt())
                        .build())
                .build();

        log.info("Step 10 OK: AuthResponse built successfully");
        log.info("=== VERIFY OTP END - SUCCESS ===");
        return response;
    }

    /**
     * Resend OTP
     */
    @Transactional
    public void resendOtp(PhoneAuthRequest request) {
        String fullPhone = request.getCountryCode() + request.getPhoneNumber();

        if (!userRepository.existsByPhoneNumber(fullPhone)) {
            throw new BadRequestException("Aucun compte trouvé pour ce numéro.");
        }

        // Delete old OTPs and send new one
        otpRepository.deleteByPhoneNumber(fullPhone);
        sendOtp(fullPhone);
    }

    /**
     * Refresh access token
     */
    public AuthResponse refreshToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Token invalide");
        }

        String refreshToken = authHeader.substring(7);
        String phoneNumber = jwtService.extractPhoneNumber(refreshToken);

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new UnauthorizedException("Utilisateur non trouvé"));

        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new UnauthorizedException("Token expiré ou invalide");
        }

        String newAccessToken = jwtService.generateToken(user);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtExpiration / 1000)
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId())
                        .phoneNumber(user.getPhoneNumber())
                        .countryCode(user.getCountryCode())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .kycLevel(user.getKycLevel())
                        .kycLevelDisplayName(user.getKycLevel().getDisplayName())
                        .transactionLimit(user.getTransactionLimit())
                        .phoneVerified(user.getPhoneVerified())
                        .emailVerified(user.getEmailVerified())
                        .profilePictureUrl(user.getProfilePictureUrl())
                        .createdAt(user.getCreatedAt())
                        .lastLoginAt(user.getLastLoginAt())
                        .build())
                .build();
    }

    /**
     * Generate and send OTP
     */
    private void sendOtp(String phoneNumber) {
        // Generate random OTP
        SecureRandom random = new SecureRandom();
        String code = String.format("%0" + otpLength + "d", random.nextInt((int) Math.pow(10, otpLength)));

        // Save OTP
        OtpCode otpCode = OtpCode.builder()
                .phoneNumber(phoneNumber)
                .code(code)
                .expiresAt(LocalDateTime.now().plusSeconds(otpExpiration))
                .build();
        otpRepository.save(otpCode);

        // Send SMS (async)
        smsService.sendOtp(phoneNumber, code);

        log.info("OTP sent to phone: {}", phoneNumber);
    }
}
