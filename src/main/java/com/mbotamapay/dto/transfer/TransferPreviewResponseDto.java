package com.mbotamapay.dto.transfer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

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

    // === Routing Info ===

    /**
     * Stratégie de routage (SINGLE, SINGLE_WITH_FALLBACK, SPLIT, BRIDGE)
     */
    private String routingStrategy;

    /**
     * Score de la route (0-100)
     */
    private Integer routingScore;

    /**
     * Gateways de fallback disponibles
     */
    private List<String> fallbackGateways;

    // === Bridge Routing Info ===

    /**
     * True si c'est un paiement via pont
     */
    private boolean isBridgePayment;

    /**
     * Description de la route bridge (ex: "SN → CI → CG")
     */
    private BridgeRouteDto bridgeRoute;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BridgeRouteDto {
        private String routeDescription;
        private List<String> bridgeCountries;
        private int hopCount;
        private BigDecimal totalFeePercent;
        private List<BridgeLegDto> legs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BridgeLegDto {
        private String from;
        private String to;
        private String gateway;
        private BigDecimal feePercent;
    }
}
