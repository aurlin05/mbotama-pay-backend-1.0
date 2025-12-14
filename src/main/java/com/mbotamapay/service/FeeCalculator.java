package com.mbotamapay.service;

import com.mbotamapay.dto.FeeBreakdown;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Service de calcul des frais de transaction
 * 
 * Formule: (Gateway % + App 2%) arrondi au % supérieur
 * Plafond: Maximum 7%
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

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Calcule les frais pour une transaction
     * 
     * @param amount            Le montant de la transaction en XOF
     * @param gatewayFeePercent Le pourcentage de frais de la passerelle (Payin +
     *                          Payout)
     * @return Le détail des frais calculés
     */
    public FeeBreakdown calculateFees(Long amount, BigDecimal gatewayFeePercent) {
        BigDecimal amountDecimal = new BigDecimal(amount);

        // 1. Calculer les frais passerelle réels
        BigDecimal gatewayFee = amountDecimal
                .multiply(gatewayFeePercent)
                .divide(HUNDRED, 0, RoundingMode.CEILING);

        // 2. Gateway + App fee (ex: 2.70% + 2% = 4.70%)
        BigDecimal totalBrutPercent = gatewayFeePercent.add(appFeePercent);

        // 3. Arrondir au % supérieur (4.70% → 5%)
        BigDecimal roundedPercent = totalBrutPercent.setScale(0, RoundingMode.CEILING);

        // 4. Vérifier si le plafond est atteint
        boolean capped = roundedPercent.compareTo(maxTotalPercent) > 0;
        if (capped) {
            roundedPercent = maxTotalPercent;
            log.info("Fee cap applied: {}% -> {}%", totalBrutPercent, maxTotalPercent);
        }

        // 5. Calculer le total arrondi
        BigDecimal totalFee = amountDecimal
                .multiply(roundedPercent)
                .divide(HUNDRED, 0, RoundingMode.CEILING);

        // 6. L'app récupère la différence
        BigDecimal appFee = totalFee.subtract(gatewayFee);
        if (appFee.compareTo(BigDecimal.ZERO) < 0) {
            appFee = BigDecimal.ZERO;
        }

        // 7. Minimum fee
        if (totalFee.longValue() < minFeeAmount) {
            totalFee = new BigDecimal(minFeeAmount);
            appFee = totalFee.subtract(gatewayFee);
            if (appFee.compareTo(BigDecimal.ZERO) < 0) {
                appFee = BigDecimal.ZERO;
            }
        }

        log.debug("Fee calculation: amount={}, gateway={}%, rounded={}%, total={}, appFee={}",
                amount, gatewayFeePercent, roundedPercent, totalFee, appFee);

        return FeeBreakdown.builder()
                .gatewayFee(gatewayFee.longValue())
                .appFee(appFee.longValue())
                .totalFee(totalFee.longValue())
                .displayPercent(roundedPercent.intValue())
                .actualGatewayPercent(gatewayFeePercent)
                .capped(capped)
                .build();
    }

    /**
     * Calcule les frais avec un pourcentage de passerelle par défaut
     */
    public FeeBreakdown calculateFees(Long amount) {
        // Default gateway fee of 2.70% (FeeXPay typical)
        return calculateFees(amount, new BigDecimal("2.70"));
    }
}
