package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.verification.MobileMoneyVerificationResult;
import com.mbotamapay.service.MobileMoneyVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller pour la vérification des numéros Mobile Money
 * 
 * Endpoints:
 * - GET /verify/local : Validation locale (préfixes)
 * - GET /verify/api : Vérification API complète
 */
@RestController
@RequestMapping("/verify")
@RequiredArgsConstructor
@Tag(name = "Verification", description = "APIs pour vérifier les numéros Mobile Money")
@Slf4j
public class VerificationController {

    private final MobileMoneyVerificationService verificationService;

    /**
     * Validation locale basée sur les préfixes téléphoniques
     * Rapide, hors ligne, vérifie si le numéro correspond à un opérateur MM connu
     */
    @GetMapping("/local")
    @Operation(summary = "Validation locale", description = "Vérifie le numéro via les préfixes (rapide, hors ligne)")
    public ResponseEntity<ApiResponse<MobileMoneyVerificationResult>> validateLocal(
            @RequestParam("phone") String phone) {

        log.info("Local validation request: {}", phone);

        MobileMoneyVerificationResult result = verificationService.validateLocal(phone);

        if (result.isValid()) {
            return ResponseEntity.ok(ApiResponse.success("Numéro valide", result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(result.getErrorMessage()));
        }
    }

    /**
     * Vérification complète via API gateway
     * Plus lente mais vérifie l'existence réelle du compte Mobile Money
     */
    @GetMapping("/api")
    @Operation(summary = "Vérification API", description = "Vérifie le numéro via l'API de la passerelle (plus fiable)")
    public ResponseEntity<ApiResponse<MobileMoneyVerificationResult>> verifyViaApi(
            @RequestParam("phone") String phone) {

        log.info("API verification request: {}", phone);

        MobileMoneyVerificationResult result = verificationService.verifyViaApi(phone);

        if (result.isValid()) {
            String message = result.isApiVerified() ? "Compte Mobile Money vérifié" : "Numéro valide (local)";
            return ResponseEntity.ok(ApiResponse.success(message, result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(result.getErrorMessage()));
        }
    }

    /**
     * Vérification rapide (alias de /local)
     */
    @GetMapping("/quick/{phone}")
    @Operation(summary = "Vérification rapide", description = "Alias de /local pour vérification rapide par path")
    public ResponseEntity<ApiResponse<MobileMoneyVerificationResult>> quickVerify(
            @PathVariable("phone") String phone) {

        MobileMoneyVerificationResult result = verificationService.validateLocal(phone);
        return ResponseEntity.ok(
                result.isValid() ? ApiResponse.success("Valide", result) : ApiResponse.error(result.getErrorMessage()));
    }
}
