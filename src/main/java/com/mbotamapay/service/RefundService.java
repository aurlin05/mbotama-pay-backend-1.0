package com.mbotamapay.service;

import com.mbotamapay.dto.refund.RefundRequest;
import com.mbotamapay.dto.refund.RefundResponse;
import com.mbotamapay.entity.Refund;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.enums.AuditAction;
import com.mbotamapay.entity.enums.RefundStatus;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.ResourceNotFoundException;
import com.mbotamapay.repository.RefundRepository;
import com.mbotamapay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refund Service
 * Handles refund requests and processing
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final RefundRepository refundRepository;
    private final TransactionRepository transactionRepository;
    private final AuditService auditService;

    /**
     * Initiate a refund for a transaction
     */
    @Transactional
    public RefundResponse initiateRefund(Long userId, Long transactionId, RefundRequest request) {
        // Find the transaction
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction non trouvée"));

        // Verify ownership
        if (!transaction.getSender().getId().equals(userId)) {
            throw new BadRequestException(
                    "Vous n'êtes pas autorisé à demander un remboursement pour cette transaction");
        }

        // Check if transaction is refundable
        if (transaction.getStatus() != TransactionStatus.COMPLETED) {
            throw new BadRequestException("Seules les transactions complétées peuvent être remboursées");
        }

        // Check if refund already exists
        if (refundRepository.existsByTransactionId(transactionId)) {
            throw new BadRequestException("Une demande de remboursement existe déjà pour cette transaction");
        }

        // Check if within refund window (e.g., 7 days)
        LocalDateTime refundDeadline = transaction.getCompletedAt().plusDays(7);
        if (LocalDateTime.now().isAfter(refundDeadline)) {
            throw new BadRequestException("La période de remboursement de 7 jours est expirée");
        }

        // Create refund
        Refund refund = Refund.builder()
                .transaction(transaction)
                .amount(transaction.getAmount())
                .reason(request.getReason())
                .externalReference(generateRefundReference())
                .requestedBy(userId)
                .build();

        refund = refundRepository.save(refund);

        // Audit log
        auditService.logWithEntity(
                AuditAction.REFUND_INITIATED,
                "Refund",
                refund.getId(),
                String.format("Refund initiated for transaction %s, amount: %d",
                        transaction.getExternalReference(), transaction.getAmount()));

        log.info("Refund initiated: id={}, transactionId={}, amount={}",
                refund.getId(), transactionId, transaction.getAmount());

        return mapToResponse(refund);
    }

    /**
     * Process a pending refund (Admin/System)
     */
    @Transactional
    public RefundResponse processRefund(Long refundId, boolean approved, String adminNote) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Remboursement non trouvé"));

        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new BadRequestException("Ce remboursement a déjà été traité");
        }

        if (approved) {
            // In production, this would call the payment gateway's refund API
            refund.setStatus(RefundStatus.PROCESSING);
            refundRepository.save(refund);

            // Simulate gateway processing (in production, this would be async with
            // callback)
            try {
                // Call gateway refund API here
                refund.setStatus(RefundStatus.COMPLETED);
                refund.setProcessedAt(LocalDateTime.now());

                // Update original transaction
                Transaction transaction = refund.getTransaction();
                transaction.setStatus(TransactionStatus.REFUNDED);
                transactionRepository.save(transaction);

                auditService.logWithEntity(
                        AuditAction.REFUND_COMPLETED,
                        "Refund",
                        refund.getId(),
                        "Refund completed successfully");
            } catch (Exception e) {
                refund.setStatus(RefundStatus.FAILED);
                refund.setFailureReason(e.getMessage());

                auditService.logWithEntity(
                        AuditAction.REFUND_FAILED,
                        "Refund",
                        refund.getId(),
                        "Refund failed: " + e.getMessage());
            }
        } else {
            refund.setStatus(RefundStatus.REJECTED);
            refund.setFailureReason(adminNote);
            refund.setProcessedAt(LocalDateTime.now());
        }

        refund = refundRepository.save(refund);
        log.info("Refund processed: id={}, status={}", refundId, refund.getStatus());

        return mapToResponse(refund);
    }

    /**
     * Get refund by ID
     */
    public RefundResponse getRefund(Long userId, Long refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Remboursement non trouvé"));

        // Verify ownership
        if (!refund.getTransaction().getSender().getId().equals(userId)) {
            throw new BadRequestException("Accès non autorisé à ce remboursement");
        }

        return mapToResponse(refund);
    }

    /**
     * Get refunds for a user
     */
    public Page<RefundResponse> getUserRefunds(Long userId, Pageable pageable) {
        return refundRepository.findByUserId(userId, pageable)
                .map(this::mapToResponse);
    }

    /**
     * Get refund by transaction ID
     */
    public RefundResponse getRefundByTransaction(Long userId, Long transactionId) {
        Refund refund = refundRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Aucun remboursement trouvé pour cette transaction"));

        // Verify ownership
        if (!refund.getTransaction().getSender().getId().equals(userId)) {
            throw new BadRequestException("Accès non autorisé à ce remboursement");
        }

        return mapToResponse(refund);
    }

    private String generateRefundReference() {
        return "REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private RefundResponse mapToResponse(Refund refund) {
        return RefundResponse.builder()
                .id(refund.getId())
                .transactionId(refund.getTransaction().getId())
                .transactionReference(refund.getTransaction().getExternalReference())
                .amount(refund.getAmount())
                .currency(refund.getTransaction().getCurrency())
                .status(refund.getStatus())
                .reason(refund.getReason())
                .externalReference(refund.getExternalReference())
                .failureReason(refund.getFailureReason())
                .createdAt(refund.getCreatedAt())
                .processedAt(refund.getProcessedAt())
                .build();
    }
}
