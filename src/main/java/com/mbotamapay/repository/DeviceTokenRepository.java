package com.mbotamapay.repository;

import com.mbotamapay.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Device Token Repository
 */
@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    /**
     * Find all active tokens for a user
     */
    List<DeviceToken> findByUserIdAndIsActiveTrue(Long userId);

    /**
     * Find token by token string
     */
    Optional<DeviceToken> findByToken(String token);

    /**
     * Check if token exists
     */
    boolean existsByToken(String token);

    /**
     * Delete all tokens for a user
     */
    @Modifying
    @Transactional
    void deleteByUserId(Long userId);

    /**
     * Deactivate a token
     */
    @Modifying
    @Transactional
    @Query("UPDATE DeviceToken d SET d.isActive = false WHERE d.token = :token")
    void deactivateToken(String token);

    /**
     * Deactivate all tokens for a user
     */
    @Modifying
    @Transactional
    @Query("UPDATE DeviceToken d SET d.isActive = false WHERE d.user.id = :userId")
    void deactivateAllUserTokens(Long userId);
}
