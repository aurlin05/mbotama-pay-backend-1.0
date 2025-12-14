package com.mbotamapay.repository;

import com.mbotamapay.entity.Refund;
import com.mbotamapay.entity.enums.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Refund Repository
 */
@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    /**
     * Find refund by transaction ID
     */
    Optional<Refund> findByTransactionId(Long transactionId);

    /**
     * Find refund by external reference
     */
    Optional<Refund> findByExternalReference(String externalReference);

    /**
     * Find refunds by status
     */
    List<Refund> findByStatus(RefundStatus status);

    /**
     * Find refunds for a user (via transaction sender)
     */
    @Query("SELECT r FROM Refund r WHERE r.transaction.sender.id = :userId ORDER BY r.createdAt DESC")
    Page<Refund> findByUserId(Long userId, Pageable pageable);

    /**
     * Find pending refunds older than a certain time (for retry/escalation)
     */
    List<Refund> findByStatusAndCreatedAtBefore(RefundStatus status, LocalDateTime before);

    /**
     * Count refunds by status
     */
    long countByStatus(RefundStatus status);

    /**
     * Check if a transaction already has a refund
     */
    boolean existsByTransactionId(Long transactionId);
}
