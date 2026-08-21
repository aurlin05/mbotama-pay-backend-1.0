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
     * Frais réels de la passerelle, en unités mineures de {@link #currency}
     */
    private Long gatewayFee;

    /**
     * Frais MbotamaPay facturés (différence entre total arrondi et frais
     * passerelle), plancher à zéro pour l'affichage
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
     * True si le plafond de frais a été appliqué
     */
    private boolean capped;

    // --- Champs ajoutés pour rendre l'économie de la route visible au moteur ---

    /**
     * Devise dans laquelle tous les montants ci-dessus sont exprimés.
     *
     * <p>
     * Le calcul supposait auparavant XOF partout, y compris sur les corridors en
     * XAF, CDF et GNF.
     */
    private String currency;

    /**
     * Marge nette réelle, <strong>signée</strong> : {@code totalFee - gatewayFee}.
     *
     * <p>
     * Contrairement à {@link #appFee}, cette valeur n'est pas ramenée à zéro. Une
     * valeur négative signifie que la plateforme paie la différence — ce que le
     * plafond de frais provoque silencieusement dès que la passerelle dépasse
     * environ 5 %. Le scoring s'appuie sur cette valeur pour ne plus élire par
     * défaut la route la moins rentable.
     */
    private Long netMargin;

    /**
     * True si la marge obtenue est inférieure à la marge cible configurée, que ce
     * soit à cause du plafond ou de l'arrondi.
     */
    private boolean marginBelowTarget;

    /** Coût total pour le client : montant envoyé + frais. */
    public Long getTotalCharged(Long amount) {
        return amount + (totalFee == null ? 0L : totalFee);
    }
}
