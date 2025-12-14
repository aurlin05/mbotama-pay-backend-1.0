package com.mbotamapay.service;

import com.mbotamapay.dto.user.UpdateProfileRequest;
import com.mbotamapay.dto.user.UserProfileResponse;
import com.mbotamapay.entity.User;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.ResourceNotFoundException;
import com.mbotamapay.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User Service for profile management
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    /**
     * Get user profile by ID
     */
    public UserProfileResponse getProfile(Long userId) {
        User user = findUserById(userId);
        return mapToProfileResponse(user);
    }

    /**
     * Get user profile by phone number
     */
    public UserProfileResponse getProfileByPhone(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        return mapToProfileResponse(user);
    }

    /**
     * Update user profile
     */
    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = findUserById(userId);

        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getEmail() != null) {
            // Check if email is already used
            if (userRepository.existsByEmail(request.getEmail()) &&
                    !request.getEmail().equals(user.getEmail())) {
                throw new BadRequestException("Cet email est déjà utilisé");
            }
            user.setEmail(request.getEmail());
            user.setEmailVerified(false); // Reset email verification
        }
        if (request.getAddress() != null) {
            user.setAddress(request.getAddress());
        }
        if (request.getCity() != null) {
            user.setCity(request.getCity());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }

        userRepository.save(user);
        log.info("Profile updated for user: {}", userId);

        return mapToProfileResponse(user);
    }

    /**
     * Find user by ID or throw exception
     */
    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }

    /**
     * Map User entity to profile response DTO
     */
    private UserProfileResponse mapToProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .countryCode(user.getCountryCode())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .address(user.getAddress())
                .city(user.getCity())
                .dateOfBirth(user.getDateOfBirth())
                .kycLevel(user.getKycLevel())
                .kycLevelDisplayName(user.getKycLevel().getDisplayName())
                .transactionLimit(user.getTransactionLimit())
                .phoneVerified(user.getPhoneVerified())
                .emailVerified(user.getEmailVerified())
                .profilePictureUrl(user.getProfilePictureUrl())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .build();
    }
}
