package com.mbotamapay.repository;

import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Transaction repository
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Page<Transaction> findBySenderIdOrderByCreatedAtDesc(Long senderId, Pageable pageable);

    List<Transaction> findBySenderPhoneOrRecipientPhoneOrderByCreatedAtDesc(String senderPhone, String recipientPhone);

    Optional<Transaction> findByExternalReference(String externalReference);

    List<Transaction> findByStatus(TransactionStatus status);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.sender.id = :userId AND t.status = 'COMPLETED' AND t.createdAt >= :startDate")
    Long sumAmountBySenderIdAndStatusCompletedSince(Long userId, LocalDateTime startDate);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.sender.id = :userId AND t.createdAt >= :startDate")
    Long countBySenderIdSince(Long userId, LocalDateTime startDate);

    /**
     * Find transactions by status created before a certain date
     */
    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, LocalDateTime date);

    /**
     * Find transactions by status created after a certain date
     */
    List<Transaction> findByStatusAndCreatedAtAfter(TransactionStatus status, LocalDateTime date);
}
