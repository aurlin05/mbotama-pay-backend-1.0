package com.mbotamapay.service;

import com.mbotamapay.dto.transaction.TransactionRequest;
import com.mbotamapay.dto.transaction.TransactionResponse;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.KycLevel;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.ResourceNotFoundException;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transaction Service for money transfers
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    /**
     * Initiate a new transaction
     */
    @Transactional
    public TransactionResponse initiateTransaction(Long userId, TransactionRequest request) {
        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));

        // Check KYC level
        if (sender.getKycLevel() == KycLevel.NONE) {
            throw new BadRequestException(
                    "Vérification d'identité requise. Complétez votre KYC pour envoyer de l'argent.");
        }

        // Check transaction limit
        long limit = sender.getTransactionLimit();
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        Long usedAmount = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(userId, thirtyDaysAgo);
        if (usedAmount == null)
            usedAmount = 0L;

        if (usedAmount + request.getAmount() > limit) {
            throw new BadRequestException(String.format(
                    "Limite de transaction dépassée. Limite: %d FCFA, Utilisé: %d FCFA, Disponible: %d FCFA",
                    limit, usedAmount, limit - usedAmount));
        }

        // Calculate fee (example: 1% with minimum 100 XOF)
        long fee = Math.max(100L, (long) (request.getAmount() * 0.01));

        // Create transaction
        Transaction transaction = Transaction.builder()
                .sender(sender)
                .senderPhone(sender.getPhoneNumber())
                .senderName(sender.getFullName())
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .amount(request.getAmount())
                .fee(fee)
                .platform(request.getPlatform())
                .description(request.getDescription())
                .externalReference(generateReference())
                .build();

        transaction = transactionRepository.save(transaction);
        log.info("Transaction initiated: id={}, amount={}, platform={}",
                transaction.getId(), transaction.getAmount(), transaction.getPlatform());

        return mapToResponse(transaction);
    }

    /**
     * Get transaction by ID
     */
    public TransactionResponse getTransaction(Long userId, Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction non trouvée"));

        // Check ownership
        if (!transaction.getSender().getId().equals(userId)) {
            throw new BadRequestException("Accès non autorisé à cette transaction");
        }

        return mapToResponse(transaction);
    }

    /**
     * Get user transactions with pagination
     */
    public Page<TransactionResponse> getUserTransactions(Long userId, Pageable pageable) {
        return transactionRepository.findBySenderIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::mapToResponse);
    }

    private String generateReference() {
        return "MBP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private TransactionResponse mapToResponse(Transaction tx) {
        return TransactionResponse.builder()
                .id(tx.getId())
                .senderPhone(tx.getSenderPhone())
                .senderName(tx.getSenderName())
                .recipientPhone(tx.getRecipientPhone())
                .recipientName(tx.getRecipientName())
                .amount(tx.getAmount())
                .fee(tx.getFee())
                .totalAmount(tx.getTotalAmount())
                .currency(tx.getCurrency())
                .platform(tx.getPlatform())
                .status(tx.getStatus())
                .description(tx.getDescription())
                .externalReference(tx.getExternalReference())
                .createdAt(tx.getCreatedAt())
                .completedAt(tx.getCompletedAt())
                .build();
    }
}
