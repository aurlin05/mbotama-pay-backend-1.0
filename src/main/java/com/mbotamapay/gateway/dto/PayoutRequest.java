package com.mbotamapay.gateway.dto;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.MobileOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Requête de payout (envoi d'argent)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutRequest {

    /**
     * Référence unique de la transaction
     */
    private String reference;

    /**
     * Montant à envoyer en XOF
     */
    private Long amount;

    /**
     * Devise (XOF par défaut)
     */
    @Builder.Default
    private String currency = "XOF";

    /**
     * Numéro du destinataire
     */
    private String recipientPhone;

    /**
     * Nom du destinataire
     */
    private String recipientName;

    /**
     * Pays du destinataire
     */
    private Country country;

    /**
     * Opérateur mobile du destinataire (optionnel)
     */
    private MobileOperator operator;

    /**
     * Description du payout
     */
    private String description;
}
