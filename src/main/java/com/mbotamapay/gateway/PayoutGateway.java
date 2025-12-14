package com.mbotamapay.gateway;

import com.mbotamapay.dto.verification.MobileMoneyVerificationResult;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.dto.PayoutRequest;
import com.mbotamapay.gateway.dto.PayoutResponse;
import com.mbotamapay.gateway.dto.PayoutStatusResponse;

import java.util.Set;

/**
 * Interface pour les opérations de payout (envoi d'argent)
 */
public interface PayoutGateway {

    /**
     * Retourne le type de passerelle
     */
    GatewayType getGatewayType();

    /**
     * Retourne les pays supportés pour le payout
     */
    Set<Country> getSupportedPayoutCountries();

    /**
     * Vérifie si cette passerelle supporte le payout vers un pays
     */
    boolean supportsPayoutTo(Country country);

    /**
     * Initie un payout (envoi d'argent)
     */
    PayoutResponse initiatePayout(PayoutRequest request);

    /**
     * Vérifie le statut d'un payout
     */
    PayoutStatusResponse checkPayoutStatus(String reference);

    /**
     * Vérifie si un numéro a un compte Mobile Money actif
     * 
     * @param phoneNumber Numéro de téléphone normalisé
     * @param country     Pays du numéro
     * @param operator    Opérateur mobile
     * @return Résultat de la vérification
     */
    default MobileMoneyVerificationResult verifySubscriber(
            String phoneNumber, Country country, MobileOperator operator) {
        // Implémentation par défaut: pas de vérification API disponible
        return null;
    }
}
