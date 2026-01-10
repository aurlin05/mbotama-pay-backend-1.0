package com.mbotamapay.service.orchestration;

import com.mbotamapay.entity.enums.GatewayType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Moniteur de santé des passerelles de paiement
 * Implémente un Circuit Breaker pattern pour éviter les appels vers des gateways défaillantes
 */
@Component
@Slf4j
public class GatewayHealthMonitor {

    private final Map<GatewayType, GatewayHealth> healthMap = new ConcurrentHashMap<>();

    // Configuration du circuit breaker
    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration RECOVERY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration METRICS_WINDOW = Duration.ofHours(1);

    public GatewayHealthMonitor() {
        // Initialiser la santé pour toutes les gateways
        for (GatewayType gateway : GatewayType.values()) {
            healthMap.put(gateway, new GatewayHealth(gateway));
        }
    }

    /**
     * Enregistre un succès pour une gateway
     */
    public void recordSuccess(GatewayType gateway, long responseTimeMs) {
        GatewayHealth health = healthMap.get(gateway);
        if (health != null) {
            health.recordSuccess(responseTimeMs);
            log.debug("Gateway {} success recorded, responseTime={}ms", gateway, responseTimeMs);
        }
    }

    /**
     * Enregistre un échec pour une gateway
     */
    public void recordFailure(GatewayType gateway, String reason) {
        GatewayHealth health = healthMap.get(gateway);
        if (health != null) {
            health.recordFailure(reason);
            log.warn("Gateway {} failure recorded: {}", gateway, reason);
        }
    }

    /**
     * Vérifie si une gateway est disponible (circuit fermé ou half-open)
     */
    public boolean isAvailable(GatewayType gateway) {
        GatewayHealth health = healthMap.get(gateway);
        return health != null && health.isAvailable();
    }

    /**
     * Retourne le score de fiabilité (0-100)
     */
    public int getReliabilityScore(GatewayType gateway) {
        GatewayHealth health = healthMap.get(gateway);
        return health != null ? health.getReliabilityScore() : 0;
    }

    /**
     * Retourne le temps de réponse moyen en ms
     */
    public long getAverageResponseTime(GatewayType gateway) {
        GatewayHealth health = healthMap.get(gateway);
        return health != null ? health.getAverageResponseTime() : Long.MAX_VALUE;
    }

    /**
     * Retourne l'état du circuit breaker
     */
    public CircuitState getCircuitState(GatewayType gateway) {
        GatewayHealth health = healthMap.get(gateway);
        return health != null ? health.getCircuitState() : CircuitState.OPEN;
    }

    /**
     * Retourne les métriques complètes d'une gateway
     */
    public GatewayMetrics getMetrics(GatewayType gateway) {
        GatewayHealth health = healthMap.get(gateway);
        if (health == null) {
            return GatewayMetrics.unavailable(gateway);
        }
        return health.getMetrics();
    }

    /**
     * Retourne les métriques de toutes les gateways
     */
    public Map<GatewayType, GatewayMetrics> getAllMetrics() {
        Map<GatewayType, GatewayMetrics> metrics = new ConcurrentHashMap<>();
        healthMap.forEach((gateway, health) -> metrics.put(gateway, health.getMetrics()));
        return metrics;
    }

    /**
     * Reset manuel d'une gateway (pour admin)
     */
    public void resetGateway(GatewayType gateway) {
        GatewayHealth health = healthMap.get(gateway);
        if (health != null) {
            health.reset();
            log.info("Gateway {} manually reset", gateway);
        }
    }

    /**
     * Nettoyage périodique des métriques anciennes
     */
    @Scheduled(fixedRate = 300000) // Toutes les 5 minutes
    public void cleanupOldMetrics() {
        healthMap.values().forEach(GatewayHealth::cleanup);
    }

    // === Inner Classes ===

    public enum CircuitState {
        CLOSED,     // Normal, tout fonctionne
        OPEN,       // Bloqué, trop d'erreurs
        HALF_OPEN   // Test en cours après recovery timeout
    }

    /**
     * État de santé d'une gateway
     */
    private static class GatewayHealth {
        private final GatewayType gateway;
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicInteger totalSuccesses = new AtomicInteger(0);
        private final AtomicInteger totalFailures = new AtomicInteger(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private volatile Instant lastFailureTime;
        private volatile Instant lastSuccessTime;
        private volatile CircuitState circuitState = CircuitState.CLOSED;
        private volatile String lastFailureReason;

        GatewayHealth(GatewayType gateway) {
            this.gateway = gateway;
        }

        void recordSuccess(long responseTimeMs) {
            consecutiveFailures.set(0);
            totalSuccesses.incrementAndGet();
            totalResponseTime.addAndGet(responseTimeMs);
            lastSuccessTime = Instant.now();

            if (circuitState == CircuitState.HALF_OPEN) {
                circuitState = CircuitState.CLOSED;
            }
        }

        void recordFailure(String reason) {
            int failures = consecutiveFailures.incrementAndGet();
            totalFailures.incrementAndGet();
            lastFailureTime = Instant.now();
            lastFailureReason = reason;

            if (failures >= FAILURE_THRESHOLD && circuitState == CircuitState.CLOSED) {
                circuitState = CircuitState.OPEN;
            }
        }

        boolean isAvailable() {
            if (circuitState == CircuitState.CLOSED) {
                return true;
            }

            if (circuitState == CircuitState.OPEN && lastFailureTime != null) {
                if (Duration.between(lastFailureTime, Instant.now()).compareTo(RECOVERY_TIMEOUT) > 0) {
                    circuitState = CircuitState.HALF_OPEN;
                    return true;
                }
            }

            return circuitState == CircuitState.HALF_OPEN;
        }

        int getReliabilityScore() {
            int total = totalSuccesses.get() + totalFailures.get();
            if (total == 0) return 100;
            return (int) ((totalSuccesses.get() * 100.0) / total);
        }

        long getAverageResponseTime() {
            int successes = totalSuccesses.get();
            if (successes == 0) return 0;
            return totalResponseTime.get() / successes;
        }

        CircuitState getCircuitState() {
            // Refresh state
            isAvailable();
            return circuitState;
        }

        GatewayMetrics getMetrics() {
            return GatewayMetrics.builder()
                    .gateway(gateway)
                    .circuitState(getCircuitState())
                    .available(isAvailable())
                    .reliabilityScore(getReliabilityScore())
                    .averageResponseTimeMs(getAverageResponseTime())
                    .totalSuccesses(totalSuccesses.get())
                    .totalFailures(totalFailures.get())
                    .consecutiveFailures(consecutiveFailures.get())
                    .lastSuccessTime(lastSuccessTime)
                    .lastFailureTime(lastFailureTime)
                    .lastFailureReason(lastFailureReason)
                    .build();
        }

        void reset() {
            consecutiveFailures.set(0);
            circuitState = CircuitState.CLOSED;
            lastFailureReason = null;
        }

        void cleanup() {
            // Réduire les compteurs si trop anciens (sliding window)
            if (lastSuccessTime != null && 
                Duration.between(lastSuccessTime, Instant.now()).compareTo(METRICS_WINDOW) > 0) {
                totalSuccesses.set(Math.max(0, totalSuccesses.get() / 2));
                totalFailures.set(Math.max(0, totalFailures.get() / 2));
                totalResponseTime.set(totalResponseTime.get() / 2);
            }
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GatewayMetrics {
        private GatewayType gateway;
        private CircuitState circuitState;
        private boolean available;
        private int reliabilityScore;
        private long averageResponseTimeMs;
        private int totalSuccesses;
        private int totalFailures;
        private int consecutiveFailures;
        private Instant lastSuccessTime;
        private Instant lastFailureTime;
        private String lastFailureReason;

        public static GatewayMetrics unavailable(GatewayType gateway) {
            return GatewayMetrics.builder()
                    .gateway(gateway)
                    .circuitState(CircuitState.OPEN)
                    .available(false)
                    .reliabilityScore(0)
                    .build();
        }
    }
}
