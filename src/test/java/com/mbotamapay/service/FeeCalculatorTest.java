package com.mbotamapay.service;

import com.mbotamapay.dto.FeeBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour FeeCalculator
 * 
 * Formule: (Gateway % + App 2%) arrondi au % supérieur
 * Plafond: 7% maximum
 */
class FeeCalculatorTest {

    private FeeCalculator feeCalculator;

    @BeforeEach
    void setUp() {
        feeCalculator = new FeeCalculator();
        ReflectionTestUtils.setField(feeCalculator, "appFeePercent", new BigDecimal("2.0"));
        ReflectionTestUtils.setField(feeCalculator, "maxTotalPercent", new BigDecimal("7.0"));
        ReflectionTestUtils.setField(feeCalculator, "minFeeAmount", 100L);
    }

    @Test
    @DisplayName("Gateway 2.70% + App 2% = 4.70% → arrondi 5%")
    void shouldRoundUpToFivePercent() {
        // Given
        Long amount = 100_000L;
        BigDecimal gatewayFee = new BigDecimal("2.70");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getDisplayPercent()).isEqualTo(5);
        assertThat(result.getTotalFee()).isEqualTo(5000L); // 5% of 100,000
        assertThat(result.getGatewayFee()).isEqualTo(2700L); // 2.70% of 100,000
        assertThat(result.getAppFee()).isEqualTo(2300L); // 5000 - 2700
        assertThat(result.isCapped()).isFalse();
    }

    @Test
    @DisplayName("Gateway 3.10% + App 2% = 5.10% → arrondi 6%")
    void shouldRoundUpToSixPercent() {
        // Given
        Long amount = 100_000L;
        BigDecimal gatewayFee = new BigDecimal("3.10");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getDisplayPercent()).isEqualTo(6);
        assertThat(result.getTotalFee()).isEqualTo(6000L);
        assertThat(result.getGatewayFee()).isEqualTo(3100L);
        assertThat(result.getAppFee()).isEqualTo(2900L);
        assertThat(result.isCapped()).isFalse();
    }

    @Test
    @DisplayName("Gateway 4.50% + App 2% = 6.50% → arrondi 7%")
    void shouldRoundUpToSevenPercent() {
        // Given
        Long amount = 100_000L;
        BigDecimal gatewayFee = new BigDecimal("4.50");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getDisplayPercent()).isEqualTo(7);
        assertThat(result.getTotalFee()).isEqualTo(7000L);
        assertThat(result.getGatewayFee()).isEqualTo(4500L);
        assertThat(result.getAppFee()).isEqualTo(2500L);
        assertThat(result.isCapped()).isFalse();
    }

    @Test
    @DisplayName("Gateway 6% + App 2% = 8% → plafonné à 7%")
    void shouldCapAtSevenPercent() {
        // Given
        Long amount = 100_000L;
        BigDecimal gatewayFee = new BigDecimal("6.00");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getDisplayPercent()).isEqualTo(7);
        assertThat(result.getTotalFee()).isEqualTo(7000L);
        assertThat(result.getGatewayFee()).isEqualTo(6000L);
        assertThat(result.getAppFee()).isEqualTo(1000L); // 7000 - 6000
        assertThat(result.isCapped()).isTrue();
    }

    @Test
    @DisplayName("Gateway 7% + App 2% = 9% → plafonné à 7%")
    void shouldCapAtSevenPercentWithHighGatewayFee() {
        // Given
        Long amount = 100_000L;
        BigDecimal gatewayFee = new BigDecimal("7.00");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getDisplayPercent()).isEqualTo(7);
        assertThat(result.getTotalFee()).isEqualTo(7000L);
        assertThat(result.getGatewayFee()).isEqualTo(7000L);
        assertThat(result.getAppFee()).isEqualTo(0L); // Pas de marge pour l'app
        assertThat(result.isCapped()).isTrue();
    }

    @Test
    @DisplayName("Minimum 100 XOF pour petits montants")
    void shouldApplyMinimumFee() {
        // Given
        Long amount = 1000L; // 5% = 50 XOF < 100 XOF
        BigDecimal gatewayFee = new BigDecimal("2.70");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getTotalFee()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Gateway 3% exact + App 2% = 5% (pas d'arrondi)")
    void shouldNotRoundWhenExact() {
        // Given
        Long amount = 100_000L;
        BigDecimal gatewayFee = new BigDecimal("3.00");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getDisplayPercent()).isEqualTo(5);
        assertThat(result.getTotalFee()).isEqualTo(5000L);
        assertThat(result.getGatewayFee()).isEqualTo(3000L);
        assertThat(result.getAppFee()).isEqualTo(2000L); // Exactement 2%
    }

    @Test
    @DisplayName("Exemple: 50,000 XOF avec gateway 2.70%")
    void shouldCalculateFor50k() {
        // Given
        Long amount = 50_000L;
        BigDecimal gatewayFee = new BigDecimal("2.70");

        // When
        FeeBreakdown result = feeCalculator.calculateFees(amount, gatewayFee);

        // Then
        assertThat(result.getDisplayPercent()).isEqualTo(5);
        assertThat(result.getTotalFee()).isEqualTo(2500L); // 5% of 50,000
        assertThat(result.getGatewayFee()).isEqualTo(1350L); // 2.70% of 50,000
        assertThat(result.getAppFee()).isEqualTo(1150L);
    }
}
