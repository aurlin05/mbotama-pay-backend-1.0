package com.mbotamapay.repository;

import com.mbotamapay.entity.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * OTP repository for verification code operations
 */
@Repository
public interface OtpRepository extends JpaRepository<OtpCode, Long> {

    Optional<OtpCode> findFirstByPhoneNumberAndVerifiedFalseOrderByCreatedAtDesc(String phoneNumber);

    @Modifying
    @Transactional
    @Query("DELETE FROM OtpCode o WHERE o.expiresAt < :now")
    void deleteExpiredCodes(LocalDateTime now);

    @Modifying
    @Transactional
    void deleteByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumberAndVerifiedFalseAndExpiresAtAfter(String phoneNumber, LocalDateTime now);

    /**
     * Find expired OTP codes that haven't been verified (for cleanup job)
     */
    List<OtpCode> findByExpiresAtBeforeAndVerifiedFalse(LocalDateTime cutoff);
}
