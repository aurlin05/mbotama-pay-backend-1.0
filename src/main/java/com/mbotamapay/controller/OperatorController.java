package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.operator.CountryOperatorsDto;
import com.mbotamapay.service.OperatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller pour récupérer les opérateurs Mobile Money
 * 
 * Endpoints:
 * - GET /operators/by-phone?phone=... : Opérateurs par numéro de téléphone
 * - GET /operators/by-country?country=... : Opérateurs par code pays
 */
@RestController
@RequestMapping("/operators")
@RequiredArgsConstructor
@Tag(name = "Operators", description = "APIs pour les opérateurs Mobile Money")
@Slf4j
public class OperatorController {

    private final OperatorService operatorService;

    /**
     * Récupère les opérateurs disponibles pour un numéro de téléphone
     * Détecte automatiquement le pays et retourne les opérateurs MM disponibles
     */
    @GetMapping("/by-phone")
    @Operation(summary = "Opérateurs par téléphone", description = "Détecte le pays et retourne les opérateurs Mobile Money disponibles")
    public ResponseEntity<ApiResponse<CountryOperatorsDto>> getByPhone(
            @RequestParam("phone") String phone) {

        log.info("Get operators by phone: {}", phone);

        CountryOperatorsDto result = operatorService.getOperatorsByPhone(phone);

        if (result.getCountry() == null) {
            return ResponseEntity.ok(ApiResponse.error("Pays non reconnu pour ce numéro"));
        }

        return ResponseEntity.ok(ApiResponse.success(
                "Opérateurs disponibles pour " + result.getCountry().getName(),
                result));
    }

    /**
     * Récupère les opérateurs disponibles pour un code pays ISO
     */
    @GetMapping("/by-country")
    @Operation(summary = "Opérateurs par pays", description = "Retourne les opérateurs Mobile Money disponibles pour un pays")
    public ResponseEntity<ApiResponse<CountryOperatorsDto>> getByCountry(
            @RequestParam("country") String country) {

        log.info("Get operators by country: {}", country);

        CountryOperatorsDto result = operatorService.getOperatorsByCountryCode(country);

        if (result.getCountry() == null) {
            return ResponseEntity.ok(ApiResponse.error("Code pays non reconnu: " + country));
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Vérifie si un code opérateur est valide
     */
    @GetMapping("/validate/{operatorCode}")
    @Operation(summary = "Valider un opérateur", description = "Vérifie si un code opérateur existe")
    public ResponseEntity<ApiResponse<Boolean>> validateOperator(
            @PathVariable("operatorCode") String operatorCode) {

        boolean valid = operatorService.isValidOperator(operatorCode);

        if (valid) {
            return ResponseEntity.ok(ApiResponse.success("Opérateur valide", true));
        } else {
            return ResponseEntity.ok(ApiResponse.error("Opérateur non reconnu: " + operatorCode));
        }
    }
}
