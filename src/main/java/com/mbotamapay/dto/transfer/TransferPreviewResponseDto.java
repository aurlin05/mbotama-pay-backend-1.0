package com.mbotamapay.dto.transfer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Réponse de preview de transfert
 * Montre les frais et la route avant exécution
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferPreviewResponseDto {

    /**
     * True si une route est disponible
     */
    private boolean available;

    /**
     * Montant à envoyer
     */
    private Long amount;

    /**
     * Frais totaux
     */
    private Long fee;

    /**
     * Montant total (amount + fee)
     */
    private Long totalAmount;

    /**
     * Pourcentage de frais affiché au client
     */
    private Integer feePercent;

    /**
     * Frais de la passerelle
     */
    private Long gatewayFee;

    /**
     * Frais de l'application
     */
    private Long appFee;

    /**
     * Nom de la passerelle sélectionnée
     */
    private String gateway;

    /**
     * Pays source détecté
     */
    private String sourceCountry;

    /**
     * Pays destination détecté
     */
    private String destCountry;

    /**
     * Nom de l'opérateur source (ex: "Orange Money")
     */
    private String sourceOperatorName;

    /**
     * Nom de l'opérateur destination (ex: "MTN MoMo")
     */
    private String destOperatorName;

    /**
     * True si le stock est utilisé
     */
    private boolean useStock;

    /**
     * Message si non disponible
     */
    private String reason;
}
