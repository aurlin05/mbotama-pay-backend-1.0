package com.mbotamapay.dto.transfer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Prévisualisation d'un transfert : prix, route retenue, devis épinglé.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferPreviewResponseDto {

    private boolean available;

    /** Montant envoyé, en devise source. */
    private Long amount;
    private Long fee;
    private Long totalAmount;
    private Integer feePercent;
    private Long gatewayFee;
    private Long appFee;

    private String gateway;
    private String sourceCountry;
    private String destCountry;
    private String sourceOperatorName;
    private String destOperatorName;

    /** Devise du montant envoyé et des frais. */
    private String sourceCurrency;

    /** Montant effectivement reçu par le bénéficiaire, dans sa devise. */
    private Long payoutAmount;
    private String payoutCurrency;

    private String routingStrategy;
    private Integer routingScore;
    private List<String> fallbackGateways;

    /**
     * Identifiant du devis à présenter à l'exécution pour garantir ce prix.
     *
     * <p>
     * Sans lui, l'exécution recalcule la route à son propre instant : si le
     * disjoncteur d'une passerelle s'ouvre entre-temps, la route change, donc le
     * prix — et le taux affiché étant un entier, le client peut voir 5 % puis
     * payer 6 %.
     */
    private String quoteId;
    private String quoteExpiresAt;

    /** Explication de la décision de routage. */
    private String reason;

    /**
     * Quand aucune route n'est disponible : le motif porte par porte, plutôt
     * qu'un score jugé insuffisant.
     */
    private List<String> rejectionReasons;
}
