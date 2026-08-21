package com.mbotamapay.service;

import com.mbotamapay.dto.FeeBreakdown;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Service de calcul des frais de transaction
 *
 * <p>
 * Formule inchangée : {@code ceil(frais passerelle % + commission app %)},
 * plafonnée. Deux corrections apportées :
 * <ul>
 * <li>le calcul est <strong>libellé dans une devise</strong>, et le plancher de
 * frais suit cette devise — un plancher de 100 unités a un sens en XOF, aucun
 * en GNF où cela représente environ sept francs CFA ;</li>
 * <li>la marge nette est exposée <strong>signée</strong>. Elle était auparavant
 * ramenée à zéro, ce qui masquait le fait que la plateforme paie la différence
 * dès que la passerelle dépasse le plafond.</li>
 * </ul>
 */
@Service
@Slf4j
public class FeeCalculator {

    @Value("${fees.app-fee-percent:2.0}")
    private BigDecimal appFeePercent;

    @Value("${fees.max-total-percent:7.0}")
    private BigDecimal maxTotalPercent;

    @Value("${fees.min-fee-amount:100}")
    private Long minFeeAmount;

    /**
     * Plancher de frais par devise, ex. {@code XOF: 100, GNF: 1500}. Les devises
     * absentes retombent sur {@code fees.min-fee-amount}.
     */
    @Value("#{${fees.min-fee-by-currency:{:}}}")
    private Map<String, Long> minFeeByCurrency;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String DEFAULT_CURRENCY = "XOF";

    /**
     * Calcule les frais pour une transaction.
     *
     * @param amount            montant en unités mineures de {@code currency}
     * @param gatewayFeePercent pourcentage de frais de la passerelle (payin +
     *                          payout)
     * @param currency          devise ISO du corridor
     */
    public FeeBreakdown calculateFees(Long amount, BigDecimal gatewayFeePercent, String currency) {
        String ccy = currency == null ? DEFAULT_CURRENCY : currency;
        BigDecimal amountDecimal = new BigDecimal(amount);

        // 1. Frais passerelle réels
        BigDecimal gatewayFee = amountDecimal
                .multiply(gatewayFeePercent)
                .divide(HUNDRED, 0, RoundingMode.CEILING);

        // 2. Passerelle + commission app (ex. 2,70 % + 2 % = 4,70 %)
        BigDecimal totalBrutPercent = gatewayFeePercent.add(appFeePercent);

        // 3. Arrondi au point supérieur (4,70 % → 5 %)
        BigDecimal roundedPercent = totalBrutPercent.setScale(0, RoundingMode.CEILING);

        // 4. Plafond
        boolean capped = roundedPercent.compareTo(maxTotalPercent) > 0;
        if (capped) {
            roundedPercent = maxTotalPercent;
            log.debug("Fee cap applied: {}% -> {}%", totalBrutPercent, maxTotalPercent);
        }

        // 5. Total arrondi
        BigDecimal totalFee = amountDecimal
                .multiply(roundedPercent)
                .divide(HUNDRED, 0, RoundingMode.CEILING);

        // 6. Plancher, dans la devise du corridor
        long floor = minFeeFor(ccy);
        if (totalFee.longValue() < floor) {
            totalFee = BigDecimal.valueOf(floor);
        }

        // 7. Marge nette signée : c'est elle qui dit la vérité économique
        BigDecimal netMargin = totalFee.subtract(gatewayFee);

        // 8. Marge facturée, plancher à zéro pour l'affichage client
        BigDecimal appFee = netMargin.max(BigDecimal.ZERO);

        BigDecimal targetMargin = amountDecimal
                .multiply(appFeePercent)
                .divide(HUNDRED, 0, RoundingMode.CEILING);
        boolean marginBelowTarget = netMargin.compareTo(targetMargin) < 0;

        if (netMargin.signum() < 0) {
            log.warn("Negative margin on route: amount={} {}, gateway={}%, charged={}%, margin={}",
                    amount, ccy, gatewayFeePercent, roundedPercent, netMargin);
        }

        return FeeBreakdown.builder()
                .gatewayFee(gatewayFee.longValue())
                .appFee(appFee.longValue())
                .totalFee(totalFee.longValue())
                .displayPercent(roundedPercent.intValue())
                .actualGatewayPercent(gatewayFeePercent)
                .capped(capped)
                .currency(ccy)
                .netMargin(netMargin.longValue())
                .marginBelowTarget(marginBelowTarget)
                .build();
    }

    /**
     * Variante historique, en XOF implicite. Conservée pour les appelants qui ne
     * disposent pas encore de la devise du corridor.
     */
    public FeeBreakdown calculateFees(Long amount, BigDecimal gatewayFeePercent) {
        return calculateFees(amount, gatewayFeePercent, DEFAULT_CURRENCY);
    }

    /**
     * Calcule les frais avec un pourcentage de passerelle par défaut
     */
    public FeeBreakdown calculateFees(Long amount) {
        return calculateFees(amount, new BigDecimal("2.70"), DEFAULT_CURRENCY);
    }

    private long minFeeFor(String currency) {
        if (minFeeByCurrency == null || minFeeByCurrency.isEmpty()) {
            return minFeeAmount;
        }
        return minFeeByCurrency.getOrDefault(currency, minFeeAmount);
    }
}
