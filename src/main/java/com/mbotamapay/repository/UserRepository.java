package com.mbotamapay.repository;

import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.KycLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * User repository for database operations
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhoneNumber(String phoneNumber);

    Optional<User> findByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    List<User> findByKycLevel(KycLevel kycLevel);

    @Query("SELECT u FROM User u WHERE u.phoneVerified = true AND u.kycLevel = :kycLevel")
    List<User> findVerifiedUsersByKycLevel(KycLevel kycLevel);
}
