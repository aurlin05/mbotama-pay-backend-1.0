package com.mbotamapay.service;

import com.mbotamapay.dto.verification.MobileMoneyVerificationResult;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.PayoutGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service de vérification des numéros Mobile Money
 * 
 * Deux niveaux de vérification:
 * 1. Validation locale (préfixes) - rapide, hors ligne
 * 2. Vérification API (gateway) - plus fiable, vérifie l'existence du compte
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MobileMoneyVerificationService {

    private final List<PayoutGateway> payoutGateways;

    /**
     * Validation locale basée sur les préfixes téléphoniques
     * Vérifie si le numéro correspond à un opérateur Mobile Money connu
     */
    public MobileMoneyVerificationResult validateLocal(String phoneNumber) {
        log.info("Local validation for: {}", phoneNumber);

        if (phoneNumber == null || phoneNumber.isBlank()) {
            return MobileMoneyVerificationResult.builder()
                    .valid(false)
                    .errorMessage("Numéro de téléphone requis")
                    .build();
        }

        // Normaliser le numéro
        String normalized = normalizePhone(phoneNumber);

        // Détecter le pays
        Optional<Country> countryOpt = Country.fromPhoneNumber(normalized);
        if (countryOpt.isEmpty()) {
            return MobileMoneyVerificationResult.builder()
                    .valid(false)
                    .normalizedPhone(normalized)
                    .errorMessage("Pays non reconnu. Vérifiez le préfixe international.")
                    .build();
        }

        Country country = countryOpt.get();

        // Détecter l'opérateur
        Optional<MobileOperator> operatorOpt = MobileOperator.fromPhoneNumber(normalized, country);
        if (operatorOpt.isEmpty()) {
            return MobileMoneyVerificationResult.builder()
                    .valid(false)
                    .country(country.getDisplayName())
                    .normalizedPhone(normalized)
                    .errorMessage("Opérateur non reconnu pour ce numéro.")
                    .build();
        }

        MobileOperator operator = operatorOpt.get();

        // Vérifier si l'opérateur supporte Mobile Money
        boolean supportsMobileMoney = !operator.getSupportedGateways().isEmpty();

        if (!supportsMobileMoney) {
            return MobileMoneyVerificationResult.builder()
                    .valid(false)
                    .country(country.getDisplayName())
                    .operator(operator.getDisplayName())
                    .operatorCode(operator.name())
                    .normalizedPhone(normalized)
                    .mobileMoneySupported(false)
                    .errorMessage("Cet opérateur ne supporte pas Mobile Money.")
                    .build();
        }

        return MobileMoneyVerificationResult.builder()
                .valid(true)
                .apiVerified(false)
                .country(country.getDisplayName())
                .operator(operator.getDisplayName())
                .operatorCode(operator.name())
                .normalizedPhone(normalized)
                .mobileMoneySupported(true)
                .build();
    }

    /**
     * Vérification complète via API gateway
     * Vérifie l'existence réelle du compte Mobile Money
     */
    public MobileMoneyVerificationResult verifyViaApi(String phoneNumber) {
        log.info("API verification for: {}", phoneNumber);

        // D'abord validation locale
        MobileMoneyVerificationResult localResult = validateLocal(phoneNumber);
        if (!localResult.isValid()) {
            return localResult;
        }

        // Récupérer l'opérateur
        String normalized = localResult.getNormalizedPhone();
        Optional<Country> countryOpt = Country.fromPhoneNumber(normalized);
        Optional<MobileOperator> operatorOpt = countryOpt.flatMap(
                c -> MobileOperator.fromPhoneNumber(normalized, c));

        if (operatorOpt.isEmpty()) {
            return localResult;
        }

        MobileOperator operator = operatorOpt.get();
        Country country = countryOpt.get();

        // Trouver une passerelle qui supporte cet opérateur
        for (GatewayType gatewayType : operator.getSupportedGateways()) {
            Optional<PayoutGateway> gatewayOpt = findGateway(gatewayType);
            if (gatewayOpt.isPresent()) {
                PayoutGateway gateway = gatewayOpt.get();

                try {
                    // Appeler l'API de vérification
                    MobileMoneyVerificationResult apiResult = gateway.verifySubscriber(
                            normalized, country, operator);

                    if (apiResult != null) {
                        // Enrichir avec les infos locales
                        apiResult.setCountry(country.getDisplayName());
                        apiResult.setOperator(operator.getDisplayName());
                        apiResult.setOperatorCode(operator.name());
                        apiResult.setNormalizedPhone(normalized);
                        apiResult.setMobileMoneySupported(true);
                        return apiResult;
                    }
                } catch (Exception e) {
                    log.warn("API verification failed for gateway {}: {}",
                            gatewayType, e.getMessage());
                }
            }
        }

        // Si l'API échoue, retourner le résultat local
        log.info("Falling back to local validation (API unavailable)");
        return localResult;
    }

    /**
     * Normalise un numéro de téléphone
     */
    private String normalizePhone(String phone) {
        String cleaned = phone.replaceAll("[\\s\\-().+]", "");

        // Ajouter le + si commence par un code pays
        if (cleaned.startsWith("00")) {
            cleaned = cleaned.substring(2);
        }

        // Si commence par un code pays connu, ajouter +
        if (cleaned.matches("^(221|229|225|223|224|237|226|228|227|242|243).*")) {
            return "+" + cleaned;
        }

        return phone.startsWith("+") ? phone : "+" + cleaned;
    }

    private Optional<PayoutGateway> findGateway(GatewayType type) {
        return payoutGateways.stream()
                .filter(g -> g.getGatewayType() == type)
                .findFirst();
    }
}
