package com.mbotamapay.service;

import com.mbotamapay.dto.kyc.KycDocumentRequest;
import com.mbotamapay.dto.kyc.KycStatusResponse;
import com.mbotamapay.entity.KycDocument;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.DocumentType;
import com.mbotamapay.entity.enums.KycLevel;
import com.mbotamapay.entity.enums.VerificationStatus;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.exception.ResourceNotFoundException;
import com.mbotamapay.repository.KycDocumentRepository;
import com.mbotamapay.repository.TransactionRepository;
import com.mbotamapay.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * KYC Service for identity verification management
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KycService {

    private final UserRepository userRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final TransactionRepository transactionRepository;
    private final EmailService emailService;
    @org.springframework.beans.factory.annotation.Value("${kyc.auto-approve:false}")
    private boolean autoApprove;

    private static final List<DocumentType> LEVEL_1_REQUIRED_DOCS = List.of(
            DocumentType.SELFIE,
            DocumentType.NATIONAL_ID);

    private static final List<DocumentType> LEVEL_2_REQUIRED_DOCS = List.of(
            DocumentType.PROOF_OF_ADDRESS);

    /**
     * Get KYC status for a user
     */
    public KycStatusResponse getKycStatus(Long userId) {
        User user = findUserById(userId);
        List<KycDocument> documents = kycDocumentRepository.findByUserId(userId);

        // Calculate transaction limit usage (last 30 days)
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        Long usedAmount = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(userId, thirtyDaysAgo);
        if (usedAmount == null)
            usedAmount = 0L;

        long limit = user.getTransactionLimit();
        long remaining = Math.max(0, limit - usedAmount);

        // Determine next level and required documents
        KycLevel nextLevel = getNextLevel(user.getKycLevel());
        List<String> requiredDocs = getRequiredDocuments(user.getKycLevel());

        // Map submitted documents
        List<KycStatusResponse.DocumentStatus> submittedDocs = documents.stream()
                .map(doc -> KycStatusResponse.DocumentStatus.builder()
                        .documentType(doc.getDocumentType().name())
                        .status(doc.getVerificationStatus().name())
                        .rejectionReason(doc.getRejectionReason())
                        .submittedAt(doc.getSubmittedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                        .build())
                .toList();

        return KycStatusResponse.builder()
                .currentLevel(user.getKycLevel())
                .currentLevelDisplayName(user.getKycLevel().getDisplayName())
                .transactionLimit(limit)
                .transactionLimitUsed(usedAmount)
                .transactionLimitRemaining(remaining)
                .canUpgrade(nextLevel != null)
                .nextLevel(nextLevel)
                .nextLevelDisplayName(nextLevel != null ? nextLevel.getDisplayName() : null)
                .nextLevelLimit(nextLevel != null ? nextLevel.getTransactionLimit() : null)
                .requiredDocuments(requiredDocs)
                .submittedDocuments(submittedDocs)
                .build();
    }

    /**
     * Submit a KYC document
     */
    @Transactional
    public void submitDocument(Long userId, KycDocumentRequest request) {
        User user = findUserById(userId);

        // Check if document already exists
        if (kycDocumentRepository.existsByUserIdAndDocumentTypeAndVerificationStatus(
                userId, request.getDocumentType(), VerificationStatus.PENDING)) {
            throw new BadRequestException("Un document de ce type est déjà en attente de vérification");
        }

        KycDocument document = KycDocument.builder()
                .user(user)
                .documentType(request.getDocumentType())
                .documentUrl(request.getDocumentUrl())
                .build();

        kycDocumentRepository.save(document);
        log.info("KYC document submitted: userId={}, type={}", userId, request.getDocumentType());

        // Auto-approve in development/demo mode to allow immediate progression
        if (autoApprove) {
            document.setVerificationStatus(VerificationStatus.APPROVED);
            document.setVerifiedAt(java.time.LocalDateTime.now());
            kycDocumentRepository.save(document);
            log.info("KYC document auto-approved (dev mode): userId={}, type={}", userId, request.getDocumentType());
        }

        // Check if we can auto-upgrade KYC level
        checkAndUpgradeKycLevel(user);
    }

    /**
     * Check if all required documents are approved and upgrade KYC level
     */
    @Transactional
    public void checkAndUpgradeKycLevel(User user) {
        List<KycDocument> approvedDocs = kycDocumentRepository
                .findByUserIdAndVerificationStatus(user.getId(), VerificationStatus.APPROVED);

        List<DocumentType> approvedTypes = approvedDocs.stream()
                .map(doc -> doc.getDocumentType())
                .toList();

        // Check for Level 1 upgrade
        if (user.getKycLevel() == KycLevel.NONE) {
            if (approvedTypes.containsAll(LEVEL_1_REQUIRED_DOCS)) {
                user.setKycLevel(KycLevel.LEVEL_1);
                userRepository.save(user);
                log.info("User {} upgraded to KYC Level 1", user.getId());
                if (user.getEmail() != null && !user.getEmail().isBlank()) {
                    emailService.sendKycApprovedEmail(user);
                }
            }
        }
        // Check for Level 2 upgrade
        else if (user.getKycLevel() == KycLevel.LEVEL_1) {
            List<DocumentType> allRequired = new ArrayList<>(LEVEL_1_REQUIRED_DOCS);
            allRequired.addAll(LEVEL_2_REQUIRED_DOCS);
            if (approvedTypes.containsAll(allRequired)) {
                user.setKycLevel(KycLevel.LEVEL_2);
                userRepository.save(user);
                log.info("User {} upgraded to KYC Level 2", user.getId());
                if (user.getEmail() != null && !user.getEmail().isBlank()) {
                    emailService.sendKycApprovedEmail(user);
                }
            }
        }
    }

    private KycLevel getNextLevel(KycLevel current) {
        return switch (current) {
            case NONE -> KycLevel.LEVEL_1;
            case LEVEL_1 -> KycLevel.LEVEL_2;
            case LEVEL_2 -> null;
        };
    }

    private List<String> getRequiredDocuments(KycLevel current) {
        return switch (current) {
            case NONE -> LEVEL_1_REQUIRED_DOCS.stream().map(DocumentType::name).toList();
            case LEVEL_1 -> LEVEL_2_REQUIRED_DOCS.stream().map(DocumentType::name).toList();
            case LEVEL_2 -> List.of();
        };
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
    }
}
