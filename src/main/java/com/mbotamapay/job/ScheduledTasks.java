package com.mbotamapay.job;

import com.mbotamapay.entity.OtpCode;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.gateway.GatewayService;
import com.mbotamapay.gateway.PaymentGateway;
import com.mbotamapay.gateway.dto.PaymentStatusResponse;
import com.mbotamapay.repository.OtpRepository;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled Tasks
 * Handles automated maintenance and background processing
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledTasks {

    private final OtpRepository otpRepository;
    private final TransactionRepository transactionRepository;
    private final GatewayService gatewayService;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * Clean up expired OTP codes
     * Runs every hour
     */
    @Scheduled(fixedRate = 3600000) // 1 hour in milliseconds
    @Transactional
    public void cleanupExpiredOtps() {
        log.debug("Starting OTP cleanup job...");

        LocalDateTime cutoff = LocalDateTime.now();
        List<OtpCode> expiredOtps = otpRepository.findByExpiresAtBeforeAndVerifiedFalse(cutoff);

        if (!expiredOtps.isEmpty()) {
            otpRepository.deleteAll(expiredOtps);
            log.info("Cleaned up {} expired OTP codes", expiredOtps.size());
        }
    }

    /**
     * Check status of pending transactions
     * Runs every 5 minutes
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @Transactional
    public void checkPendingTransactions() {
        log.debug("Starting pending transaction check job...");

        // Find transactions that have been processing for more than 10 minutes
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        List<Transaction> pendingTransactions = transactionRepository
                .findByStatusAndCreatedAtBefore(TransactionStatus.PROCESSING, tenMinutesAgo);

        int updated = 0;
        for (Transaction transaction : pendingTransactions) {
            try {
                PaymentGateway gateway = gatewayService.getGateway(transaction.getPlatform());
                PaymentStatusResponse status = gateway.checkStatus(transaction.getExternalReference());

                switch (status.getStatus()) {
                    case "COMPLETED" -> {
                        transaction.setStatus(TransactionStatus.COMPLETED);
                        transaction.setCompletedAt(LocalDateTime.now());
                        updated++;
                    }
                    case "FAILED" -> {
                        transaction.setStatus(TransactionStatus.FAILED);
                        updated++;
                    }
                    case "CANCELLED" -> {
                        transaction.setStatus(TransactionStatus.CANCELLED);
                        updated++;
                    }
                    // PENDING/PROCESSING - no change
                }

                transactionRepository.save(transaction);
            } catch (Exception e) {
                log.error("Failed to check status for transaction {}: {}",
                        transaction.getId(), e.getMessage());
            }
        }

        if (updated > 0) {
            log.info("Updated {} pending transactions", updated);
        }
    }

    /**
     * Retry failed transactions (optional retrying)
     * Runs every 15 minutes
     * Only retries transactions failed due to temporary issues (not user
     * cancellation)
     */
    @Scheduled(fixedRate = 900000) // 15 minutes
    public void retryFailedTransactions() {
        log.debug("Starting failed transaction retry job...");

        // Find recently failed transactions (within last 2 hours)
        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);
        List<Transaction> failedTransactions = transactionRepository
                .findByStatusAndCreatedAtAfter(TransactionStatus.FAILED, twoHoursAgo);

        // Log for monitoring - actual retry logic would require user confirmation
        if (!failedTransactions.isEmpty()) {
            log.info("Found {} failed transactions in the last 2 hours that may need attention",
                    failedTransactions.size());
        }
    }

    /**
     * Clean up expired blacklisted tokens
     * Runs every hour (delegated to TokenBlacklistService)
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupBlacklistedTokens() {
        log.debug("Starting token blacklist cleanup job...");
        tokenBlacklistService.cleanupExpiredTokens();
    }

    /**
     * Mark abandoned transactions
     * Transactions stuck in PENDING for more than 24 hours are marked as EXPIRED
     * Runs once per day at midnight
     */
    @Scheduled(cron = "0 0 0 * * ?") // Every day at midnight
    @Transactional
    public void markAbandonedTransactions() {
        log.info("Starting abandoned transaction cleanup job...");

        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        List<Transaction> abandonedTransactions = transactionRepository
                .findByStatusAndCreatedAtBefore(TransactionStatus.PENDING, oneDayAgo);

        for (Transaction transaction : abandonedTransactions) {
            transaction.setStatus(TransactionStatus.EXPIRED);
            transactionRepository.save(transaction);
        }

        if (!abandonedTransactions.isEmpty()) {
            log.info("Marked {} abandoned transactions as expired", abandonedTransactions.size());
        }
    }
}
