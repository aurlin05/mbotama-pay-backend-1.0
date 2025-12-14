package com.mbotamapay.repository;

import com.mbotamapay.entity.KycDocument;
import com.mbotamapay.entity.enums.DocumentType;
import com.mbotamapay.entity.enums.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * KYC Document repository
 */
@Repository
public interface KycDocumentRepository extends JpaRepository<KycDocument, Long> {

    List<KycDocument> findByUserId(Long userId);

    List<KycDocument> findByUserIdAndVerificationStatus(Long userId, VerificationStatus status);

    Optional<KycDocument> findByUserIdAndDocumentType(Long userId, DocumentType documentType);

    List<KycDocument> findByVerificationStatus(VerificationStatus status);

    boolean existsByUserIdAndDocumentTypeAndVerificationStatus(Long userId, DocumentType documentType,
            VerificationStatus status);
}
