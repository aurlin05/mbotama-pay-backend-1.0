package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.kyc.KycDocumentRequest;
import com.mbotamapay.dto.kyc.KycStatusResponse;
import com.mbotamapay.entity.User;
import com.mbotamapay.service.KycService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * KYC Controller for identity verification
 */
@RestController
@RequestMapping("/kyc")
@RequiredArgsConstructor
@Tag(name = "KYC", description = "APIs for KYC identity verification")
@SecurityRequirement(name = "bearerAuth")
public class KycController {

    private final KycService kycService;

    @GetMapping("/status")
    @Operation(summary = "Get KYC status", description = "Returns the current KYC status and limits")
    public ResponseEntity<ApiResponse<KycStatusResponse>> getKycStatus(
            @AuthenticationPrincipal User user) {
        KycStatusResponse status = kycService.getKycStatus(user.getId());
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @PostMapping("/documents")
    @Operation(summary = "Submit KYC document", description = "Submits a document for KYC verification")
    public ResponseEntity<ApiResponse<String>> submitDocument(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody KycDocumentRequest request) {
        kycService.submitDocument(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(
                "Document soumis avec succès. Vérification en cours.",
                "DOCUMENT_SUBMITTED"));
    }

    @GetMapping("/requirements")
    @Operation(summary = "Get KYC requirements", description = "Returns the documents required for the next KYC level")
    public ResponseEntity<ApiResponse<KycRequirements>> getRequirements(
            @AuthenticationPrincipal User user) {
        KycStatusResponse status = kycService.getKycStatus(user.getId());
        KycRequirements requirements = new KycRequirements(
                status.getCurrentLevel().name(),
                status.getNextLevel() != null ? status.getNextLevel().name() : null,
                status.getNextLevelLimit(),
                status.getRequiredDocuments());
        return ResponseEntity.ok(ApiResponse.success(requirements));
    }

    record KycRequirements(
            String currentLevel,
            String nextLevel,
            Long nextLevelLimit,
            java.util.List<String> requiredDocuments) {
    }
}
