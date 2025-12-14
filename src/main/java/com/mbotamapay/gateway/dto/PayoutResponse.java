package com.mbotamapay.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Réponse de payout
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutResponse {

    /**
     * True si le payout a été initié avec succès
     */
    private boolean success;

    /**
     * Message de la passerelle
     */
    private String message;

    /**
     * Référence externe de la passerelle
     */
    private String externalReference;

    /**
     * Référence de notre transaction
     */
    private String transactionReference;

    /**
     * Statut initial du payout
     */
    private String status;
}
