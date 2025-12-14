package com.mbotamapay.dto.kyc;

import com.mbotamapay.entity.enums.KycLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * KYC status response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycStatusResponse {

    private KycLevel currentLevel;
    private String currentLevelDisplayName;
    private Long transactionLimit;
    private Long transactionLimitUsed;
    private Long transactionLimitRemaining;
    private Boolean canUpgrade;
    private KycLevel nextLevel;
    private String nextLevelDisplayName;
    private Long nextLevelLimit;
    private List<String> requiredDocuments;
    private List<DocumentStatus> submittedDocuments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentStatus {
        private String documentType;
        private String status;
        private String rejectionReason;
        private String submittedAt;
    }
}
