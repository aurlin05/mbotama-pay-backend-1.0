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
     * Déclaration unique de ce que la passerelle sait faire.
     *
     * <p>
     * C'est la source consultée par la porte d'éligibilité du moteur de routage.
     * Toutes les autres méthodes de capacité en dérivent.
     */
    GatewayCapabilities capabilities();

    /**
     * Indique si la passerelle est configurée et autorisée à traiter du trafic
     * réel. Une passerelle intégrée mais non validée en sandbox retourne false :
     * le moteur l'écarte alors avec un motif explicite plutôt que d'échouer à
     * l'exécution.
     */
    boolean isOperational();

    default Set<Country> getSupportedPayoutCountries() {
        return capabilities().payoutCountries();
    }

    default boolean supportsPayoutTo(Country country) {
        return capabilities().canPayoutTo(country);
    }

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
     * @return Résultat de la vérification, ou null si la passerelle n'offre pas ce
     *         service
     */
    default MobileMoneyVerificationResult verifySubscriber(
            String phoneNumber, Country country, MobileOperator operator) {
        return null;
    }
}
