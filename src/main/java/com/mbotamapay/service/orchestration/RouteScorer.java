package com.mbotamapay.service.orchestration;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.repository.GatewayStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Calcule un score multi-critères pour chaque route de paiement
 * 
 * Critères pondérés:
 * - Coût (frais gateway) : 30%
 * - Fiabilité (taux de succès) : 30%
 * - Vitesse (temps de réponse) : 15%
 * - Stock disponible : 15%
 * - Support opérateur : 10%
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RouteScorer {

    private final GatewayHealthMonitor healthMonitor;
    private final GatewayStockRepository stockRepository;

    // Poids des critères (total = 100)
    @Value("${routing.score.weight.cost:30}")
    private int weightCost;

    @Value("${routing.score.weight.reliability:30}")
    private int weightReliability;

    @Value("${routing.score.weight.speed:15}")
    private int weightSpeed;

    @Value("${routing.score.weight.stock:15}")
    private int weightStock;

    @Value("${routing.score.weight.operator:10}")
    private int weightOperator;

    // Seuils de référence
    private static final BigDecimal MAX_FEE_PERCENT = new BigDecimal("5.0");
    private static final long MAX_RESPONSE_TIME_MS = 10000;
    private static final long IDEAL_STOCK_MULTIPLIER = 10; // Stock idéal = 10x le montant

    /**
     * Calcule le score global d'une route (0-100)
     */
    public RouteScore calculateScore(GatewayRoute route, Long amount, Country destCountry,
                                     MobileOperator destOperator) {
        GatewayType gateway = route.getGateway();

        // Vérifier disponibilité (circuit breaker)
        if (!healthMonitor.isAvailable(gateway)) {
            return RouteScore.unavailable(route, "Circuit breaker ouvert");
        }

        // Calculer chaque composante du score
        int costScore = calculateCostScore(route.getGatewayFeePercent());
        int reliabilityScore = calculateReliabilityScore(gateway);
        int speedScore = calculateSpeedScore(gateway);
        int stockScore = calculateStockScore(gateway, destCountry, amount);
        int operatorScore = calculateOperatorScore(gateway, destOperator);

        // Score pondéré
        int totalScore = (costScore * weightCost +
                         reliabilityScore * weightReliability +
                         speedScore * weightSpeed +
                         stockScore * weightStock +
                         operatorScore * weightOperator) / 100;

        RouteScore score = RouteScore.builder()
                .route(route)
                .totalScore(totalScore)
                .costScore(costScore)
                .reliabilityScore(reliabilityScore)
                .speedScore(speedScore)
                .stockScore(stockScore)
                .operatorScore(operatorScore)
                .available(true)
                .build();

        log.debug("Route score: {} -> {} via {} = {} (cost={}, rel={}, speed={}, stock={}, op={})",
                route.getSourceCountry(), route.getDestCountry(), gateway, totalScore,
                costScore, reliabilityScore, speedScore, stockScore, operatorScore);

        return score;
    }

    /**
     * Score basé sur les frais (moins cher = meilleur score)
     */
    private int calculateCostScore(BigDecimal feePercent) {
        if (feePercent == null || feePercent.compareTo(BigDecimal.ZERO) <= 0) {
            return 100;
        }
        if (feePercent.compareTo(MAX_FEE_PERCENT) >= 0) {
            return 0;
        }
        // Linéaire inversé: 0% = 100, MAX_FEE% = 0
        double ratio = 1.0 - (feePercent.doubleValue() / MAX_FEE_PERCENT.doubleValue());
        return (int) (ratio * 100);
    }

    /**
     * Score basé sur la fiabilité historique
     */
    private int calculateReliabilityScore(GatewayType gateway) {
        return healthMonitor.getReliabilityScore(gateway);
    }

    /**
     * Score basé sur le temps de réponse moyen
     */
    private int calculateSpeedScore(GatewayType gateway) {
        long avgResponseTime = healthMonitor.getAverageResponseTime(gateway);
        if (avgResponseTime <= 0) {
            return 80; // Pas de données = score neutre
        }
        if (avgResponseTime >= MAX_RESPONSE_TIME_MS) {
            return 0;
        }
        double ratio = 1.0 - ((double) avgResponseTime / MAX_RESPONSE_TIME_MS);
        return (int) (ratio * 100);
    }

    /**
     * Score basé sur le stock disponible
     */
    private int calculateStockScore(GatewayType gateway, Country country, Long amount) {
        Optional<GatewayStock> stockOpt = stockRepository.findByGatewayAndCountry(gateway, country);
        
        if (stockOpt.isEmpty()) {
            return 50; // Pas de stock configuré = score neutre
        }

        GatewayStock stock = stockOpt.get();
        long balance = stock.getBalance();
        
        if (balance < amount) {
            return 0; // Stock insuffisant
        }

        long idealStock = amount * IDEAL_STOCK_MULTIPLIER;
        if (balance >= idealStock) {
            return 100;
        }

        // Score proportionnel entre amount et idealStock
        double ratio = (double) (balance - amount) / (idealStock - amount);
        return (int) (ratio * 100);
    }

    /**
     * Score basé sur le support de l'opérateur destination
     */
    private int calculateOperatorScore(GatewayType gateway, MobileOperator operator) {
        if (operator == null) {
            return 70; // Opérateur inconnu = score neutre
        }
        return operator.supportsGateway(gateway) ? 100 : 0;
    }

    // === Inner Classes ===

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RouteScore implements Comparable<RouteScore> {
        private GatewayRoute route;
        private int totalScore;
        private int costScore;
        private int reliabilityScore;
        private int speedScore;
        private int stockScore;
        private int operatorScore;
        private boolean available;
        private String unavailableReason;

        public static RouteScore unavailable(GatewayRoute route, String reason) {
            return RouteScore.builder()
                    .route(route)
                    .totalScore(0)
                    .available(false)
                    .unavailableReason(reason)
                    .build();
        }

        @Override
        public int compareTo(RouteScore other) {
            // Tri décroissant par score
            return Integer.compare(other.totalScore, this.totalScore);
        }
    }
}
