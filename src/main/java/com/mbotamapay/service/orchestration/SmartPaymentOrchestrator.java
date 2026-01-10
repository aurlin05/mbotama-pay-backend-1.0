package com.mbotamapay.service.orchestration;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.PayoutGateway;
import com.mbotamapay.gateway.dto.PayoutRequest;
import com.mbotamapay.gateway.dto.PayoutResponse;
import com.mbotamapay.repository.GatewayRouteRepository;
import com.mbotamapay.repository.GatewayStockRepository;
import com.mbotamapay.service.FeeCalculator;
import com.mbotamapay.service.orchestration.RouteScorer.RouteScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrateur intelligent de paiements
 * 
 * Fonctionnalités:
 * - Scoring multi-critères des routes
 * - Fallback automatique avec retry
 * - Split routing pour gros montants
 * - Circuit breaker intégré
 * - Métriques temps réel
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartPaymentOrchestrator {

    private final GatewayRouteRepository routeRepository;
    private final GatewayStockRepository stockRepository;
    private final GatewayHealthMonitor healthMonitor;
    private final RouteScorer routeScorer;
    private final FeeCalculator feeCalculator;
    private final List<PayoutGateway> payoutGateways;

    @Value("${routing.max-retries:3}")
    private int maxRetries;

    @Value("${routing.split-threshold:5000000}")
    private long splitThreshold; // 5M XOF

    @Value("${routing.min-score-threshold:30}")
    private int minScoreThreshold;

    @Value("${routing.prefer-same-gateway:true}")
    private boolean preferSameGateway;

    /**
     * Détermine la meilleure stratégie de routage
     */
    public OrchestrationResult orchestrate(OrchestrationRequest request) {
        log.info("Orchestrating payment: {} -> {}, amount={}",
                request.getSenderPhone(), request.getRecipientPhone(), request.getAmount());

        long startTime = System.currentTimeMillis();

        // 1. Détecter pays et opérateurs
        Optional<Country> sourceOpt = Country.fromPhoneNumber(request.getSenderPhone());
        Optional<Country> destOpt = Country.fromPhoneNumber(request.getRecipientPhone());

        if (sourceOpt.isEmpty() || destOpt.isEmpty()) {
            return OrchestrationResult.failed("Pays non détecté");
        }

        Country source = sourceOpt.get();
        Country dest = destOpt.get();
        Optional<MobileOperator> destOperator = MobileOperator.fromPhoneNumber(request.getRecipientPhone(), dest);

        // 2. Vérifier si split routing nécessaire
        if (request.getAmount() > splitThreshold) {
            return orchestrateSplitPayment(request, source, dest, destOperator);
        }

        // 3. Trouver et scorer toutes les routes
        List<GatewayRoute> routes = routeRepository.findActiveRoutes(source, dest);
        if (routes.isEmpty()) {
            return OrchestrationResult.failed("Aucune route disponible pour " + source + " -> " + dest);
        }

        List<RouteScore> scoredRoutes = routes.stream()
                .map(route -> routeScorer.calculateScore(route, request.getAmount(), dest, destOperator.orElse(null)))
                .filter(score -> score.isAvailable() && score.getTotalScore() >= minScoreThreshold)
                .sorted()
                .collect(Collectors.toList());

        if (scoredRoutes.isEmpty()) {
            return OrchestrationResult.failed("Aucune route viable (toutes sous le seuil de score)");
        }

        // 4. Construire la stratégie avec fallbacks
        RoutingStrategy strategy = buildStrategy(scoredRoutes, request.getAmount(), dest);

        // 5. Calculer les frais
        RouteScore primaryRoute = scoredRoutes.get(0);
        FeeBreakdown fees = feeCalculator.calculateFees(
                request.getAmount(), 
                primaryRoute.getRoute().getGatewayFeePercent()
        );

        long orchestrationTime = System.currentTimeMillis() - startTime;

        return OrchestrationResult.builder()
                .success(true)
                .sourceCountry(source)
                .destCountry(dest)
                .destOperator(destOperator.orElse(null))
                .strategy(strategy)
                .fees(fees)
                .scoredRoutes(scoredRoutes)
                .orchestrationTimeMs(orchestrationTime)
                .build();
    }

    /**
     * Exécute le payout avec fallback automatique
     */
    @Transactional
    public PayoutExecutionResult executeWithFallback(OrchestrationResult orchestration, PayoutRequest request) {
        RoutingStrategy strategy = orchestration.getStrategy();
        List<GatewayType> gatewaysToTry = strategy.getOrderedGateways();

        PayoutExecutionResult result = null;
        List<FailedAttempt> failedAttempts = new ArrayList<>();

        for (int attempt = 0; attempt < Math.min(gatewaysToTry.size(), maxRetries); attempt++) {
            GatewayType gateway = gatewaysToTry.get(attempt);
            
            log.info("Payout attempt {}/{} via {}", attempt + 1, maxRetries, gateway);
            long startTime = System.currentTimeMillis();

            try {
                PayoutGateway payoutGateway = findPayoutGateway(gateway);
                PayoutResponse response = payoutGateway.initiatePayout(request);
                long responseTime = System.currentTimeMillis() - startTime;

                if (response.isSuccess()) {
                    healthMonitor.recordSuccess(gateway, responseTime);
                    
                    // Débiter le stock si nécessaire
                    if (strategy.isUseStock()) {
                        debitStock(gateway, orchestration.getDestCountry(), request.getAmount());
                    }

                    result = PayoutExecutionResult.builder()
                            .success(true)
                            .gateway(gateway)
                            .response(response)
                            .attemptNumber(attempt + 1)
                            .totalAttempts(attempt + 1)
                            .failedAttempts(failedAttempts)
                            .executionTimeMs(responseTime)
                            .build();
                    
                    log.info("Payout successful via {} in {}ms", gateway, responseTime);
                    return result;
                } else {
                    healthMonitor.recordFailure(gateway, response.getMessage());
                    failedAttempts.add(new FailedAttempt(gateway, response.getMessage(), responseTime));
                    log.warn("Payout failed via {}: {}", gateway, response.getMessage());
                }
            } catch (Exception e) {
                long responseTime = System.currentTimeMillis() - startTime;
                healthMonitor.recordFailure(gateway, e.getMessage());
                failedAttempts.add(new FailedAttempt(gateway, e.getMessage(), responseTime));
                log.error("Payout error via {}: {}", gateway, e.getMessage());
            }
        }

        // Tous les essais ont échoué
        return PayoutExecutionResult.builder()
                .success(false)
                .totalAttempts(failedAttempts.size())
                .failedAttempts(failedAttempts)
                .errorMessage("Tous les essais ont échoué après " + failedAttempts.size() + " tentatives")
                .build();
    }

    /**
     * Orchestration pour les gros montants (split en plusieurs transactions)
     */
    private OrchestrationResult orchestrateSplitPayment(OrchestrationRequest request, 
            Country source, Country dest, Optional<MobileOperator> destOperator) {
        
        log.info("Split payment required for amount {}", request.getAmount());

        List<GatewayRoute> routes = routeRepository.findActiveRoutes(source, dest);
        if (routes.isEmpty()) {
            return OrchestrationResult.failed("Aucune route disponible");
        }

        // Calculer la capacité de chaque gateway
        Map<GatewayType, Long> gatewayCapacity = new HashMap<>();
        for (GatewayRoute route : routes) {
            Optional<GatewayStock> stock = stockRepository.findByGatewayAndCountry(route.getGateway(), dest);
            long capacity = stock.map(GatewayStock::getBalance).orElse(0L);
            gatewayCapacity.merge(route.getGateway(), capacity, Long::max);
        }

        // Construire le plan de split
        List<SplitPart> splitParts = new ArrayList<>();
        long remainingAmount = request.getAmount();

        List<Map.Entry<GatewayType, Long>> sortedGateways = gatewayCapacity.entrySet().stream()
                .filter(e -> healthMonitor.isAvailable(e.getKey()))
                .sorted(Map.Entry.<GatewayType, Long>comparingByValue().reversed())
                .collect(Collectors.toList());

        for (Map.Entry<GatewayType, Long> entry : sortedGateways) {
            if (remainingAmount <= 0) break;

            GatewayType gateway = entry.getKey();
            long capacity = entry.getValue();
            long partAmount = Math.min(remainingAmount, capacity);

            if (partAmount > 0) {
                splitParts.add(new SplitPart(gateway, partAmount));
                remainingAmount -= partAmount;
            }
        }

        if (remainingAmount > 0) {
            return OrchestrationResult.failed(
                    "Capacité insuffisante pour le montant. Manque: " + remainingAmount + " XOF");
        }

        RoutingStrategy strategy = RoutingStrategy.builder()
                .type(RoutingStrategyType.SPLIT)
                .splitParts(splitParts)
                .totalAmount(request.getAmount())
                .build();

        return OrchestrationResult.builder()
                .success(true)
                .sourceCountry(source)
                .destCountry(dest)
                .destOperator(destOperator.orElse(null))
                .strategy(strategy)
                .isSplitPayment(true)
                .build();
    }

    /**
     * Construit la stratégie de routage avec fallbacks
     */
    private RoutingStrategy buildStrategy(List<RouteScore> scoredRoutes, Long amount, Country dest) {
        List<GatewayType> orderedGateways = scoredRoutes.stream()
                .map(score -> score.getRoute().getGateway())
                .distinct()
                .collect(Collectors.toList());

        RouteScore primary = scoredRoutes.get(0);
        boolean useStock = checkStockNeeded(primary.getRoute().getGateway(), dest, amount);

        return RoutingStrategy.builder()
                .type(RoutingStrategyType.SINGLE_WITH_FALLBACK)
                .primaryGateway(primary.getRoute().getGateway())
                .orderedGateways(orderedGateways)
                .primaryScore(primary.getTotalScore())
                .useStock(useStock)
                .totalAmount(amount)
                .build();
    }

    private boolean checkStockNeeded(GatewayType gateway, Country country, Long amount) {
        Optional<GatewayStock> stock = stockRepository.findByGatewayAndCountry(gateway, country);
        return stock.isPresent() && stock.get().hasSufficientBalance(amount);
    }

    private void debitStock(GatewayType gateway, Country country, Long amount) {
        Optional<GatewayStock> stockOpt = stockRepository.findByGatewayAndCountryForUpdate(gateway, country);
        if (stockOpt.isPresent()) {
            GatewayStock stock = stockOpt.get();
            stock.debit(amount);
            stockRepository.save(stock);
            log.info("Stock debited: gateway={}, country={}, amount={}, newBalance={}",
                    gateway, country, amount, stock.getBalance());
        }
    }

    private PayoutGateway findPayoutGateway(GatewayType type) {
        return payoutGateways.stream()
                .filter(g -> g.getGatewayType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Gateway not found: " + type));
    }

    // === Inner Classes ===

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OrchestrationRequest {
        private String senderPhone;
        private String recipientPhone;
        private String recipientName;
        private Long amount;
        private String currency;
        private String description;
        private Map<String, Object> metadata;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OrchestrationResult {
        private boolean success;
        private String errorMessage;
        private Country sourceCountry;
        private Country destCountry;
        private MobileOperator destOperator;
        private RoutingStrategy strategy;
        private FeeBreakdown fees;
        private List<RouteScore> scoredRoutes;
        private boolean isSplitPayment;
        private long orchestrationTimeMs;

        public static OrchestrationResult failed(String message) {
            return OrchestrationResult.builder()
                    .success(false)
                    .errorMessage(message)
                    .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RoutingStrategy {
        private RoutingStrategyType type;
        private GatewayType primaryGateway;
        private List<GatewayType> orderedGateways;
        private List<SplitPart> splitParts;
        private int primaryScore;
        private boolean useStock;
        private Long totalAmount;
    }

    public enum RoutingStrategyType {
        SINGLE,                 // Une seule gateway, pas de fallback
        SINGLE_WITH_FALLBACK,   // Gateway principale + fallbacks
        SPLIT,                  // Montant divisé entre plusieurs gateways
        HYBRID                  // Combinaison split + fallback
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SplitPart {
        private GatewayType gateway;
        private Long amount;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PayoutExecutionResult {
        private boolean success;
        private GatewayType gateway;
        private PayoutResponse response;
        private int attemptNumber;
        private int totalAttempts;
        private List<FailedAttempt> failedAttempts;
        private long executionTimeMs;
        private String errorMessage;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class FailedAttempt {
        private GatewayType gateway;
        private String reason;
        private long responseTimeMs;
    }
}
