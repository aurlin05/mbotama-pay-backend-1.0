package com.mbotamapay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Détail des frais calculés pour une transaction
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeeBreakdown {

    /**
     * Frais réels de la passerelle en XOF
     */
    private Long gatewayFee;

    /**
     * Frais MbotamaPay (différence entre total arrondi et frais passerelle)
     */
    private Long appFee;

    /**
     * Montant total des frais prélevés (arrondi)
     */
    private Long totalFee;

    /**
     * Pourcentage affiché au client (arrondi au supérieur)
     */
    private Integer displayPercent;

    /**
     * Pourcentage réel de la passerelle (Payin + Payout)
     */
    private BigDecimal actualGatewayPercent;

    /**
     * True si le plafond de 7% a été appliqué
     */
    private boolean capped;
}
