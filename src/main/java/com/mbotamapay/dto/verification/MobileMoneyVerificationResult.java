package com.mbotamapay.dto.verification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Résultat de la vérification d'un numéro Mobile Money
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobileMoneyVerificationResult {

    /**
     * True si le numéro est valide et Mobile Money activé
     */
    private boolean valid;

    /**
     * True si vérifié via API gateway (sinon validation locale)
     */
    private boolean apiVerified;

    /**
     * Nom du titulaire du compte (si disponible via API)
     */
    private String accountName;

    /**
     * Pays détecté
     */
    private String country;

    /**
     * Opérateur détecté
     */
    private String operator;

    /**
     * Code opérateur (ex: ORANGE_SN)
     */
    private String operatorCode;

    /**
     * True si l'opérateur supporte Mobile Money
     */
    private boolean mobileMoneySupported;

    /**
     * Message d'erreur si invalide
     */
    private String errorMessage;

    /**
     * Numéro normalisé au format international
     */
    private String normalizedPhone;
}
