package com.mbotamapay.service.orchestration;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Analytics et métriques du système de routage
 * Collecte des données pour optimisation et reporting
 */
@Component
@Slf4j
public class RoutingAnalytics {

    // Métriques par gateway
    private final Map<GatewayType, GatewayStats> gatewayStats = new ConcurrentHashMap<>();

    // Métriques par corridor
    private final Map<String, CorridorStats> corridorStats = new ConcurrentHashMap<>();

    // Métriques globales
    private final AtomicLong totalTransactions = new AtomicLong(0);
    private final AtomicLong totalSuccessful = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalVolume = new AtomicLong(0);
    private final AtomicLong totalFees = new AtomicLong(0);

    // Métriques bridge routing
    private final AtomicLong totalBridgeTransactions = new AtomicLong(0);
    private final AtomicLong successfulBridgeTransactions = new AtomicLong(0);
    private final Map<String, BridgeStats> bridgeStats = new ConcurrentHashMap<>();

    // Historique pour tendances
    private final Map<LocalDate, DailyStats> dailyHistory = new ConcurrentHashMap<>();

    // Alertes actives
    private final List<RoutingAlert> activeAlerts = Collections.synchronizedList(new ArrayList<>());

    public RoutingAnalytics() {
        // Initialiser les stats pour chaque gateway
        for (GatewayType gateway : GatewayType.values()) {
            gatewayStats.put(gateway, new GatewayStats(gateway));
        }
    }

    // === Enregistrement des événements ===

    /**
     * Enregistre une transaction réussie
     */
    public void recordSuccess(GatewayType gateway, Country source, Country dest, 
                              Long amount, Long fee, long responseTimeMs) {
        totalTransactions.incrementAndGet();
        totalSuccessful.incrementAndGet();
        totalVolume.addAndGet(amount);
        totalFees.addAndGet(fee);

        // Stats gateway
        GatewayStats gStats = gatewayStats.get(gateway);
        if (gStats != null) {
            gStats.recordSuccess(amount, fee, responseTimeMs);
        }

        // Stats corridor
        String corridorKey = source.getIsoCode() + "->" + dest.getIsoCode();
        corridorStats.computeIfAbsent(corridorKey, k -> new CorridorStats(source, dest))
                .recordSuccess(gateway, amount, fee, responseTimeMs);

        // Stats journalières
        dailyHistory.computeIfAbsent(LocalDate.now(), k -> new DailyStats())
                .recordSuccess(amount, fee);

        log.debug("Analytics: success recorded for {} via {}, amount={}", corridorKey, gateway, amount);
    }

    /**
     * Enregistre une transaction échouée
     */
    public void recordFailure(GatewayType gateway, Country source, Country dest, 
                              Long amount, String reason) {
        totalTransactions.incrementAndGet();
        totalFailed.incrementAndGet();

        // Stats gateway
        GatewayStats gStats = gatewayStats.get(gateway);
        if (gStats != null) {
            gStats.recordFailure(reason);
        }

        // Stats corridor
        String corridorKey = source.getIsoCode() + "->" + dest.getIsoCode();
        corridorStats.computeIfAbsent(corridorKey, k -> new CorridorStats(source, dest))
                .recordFailure(gateway, reason);

        // Stats journalières
        dailyHistory.computeIfAbsent(LocalDate.now(), k -> new DailyStats())
                .recordFailure();

        // Vérifier si une alerte doit être déclenchée
        checkAndTriggerAlerts(gateway, source, dest);

        log.debug("Analytics: failure recorded for {} via {}, reason={}", corridorKey, gateway, reason);
    }

    /**
     * Enregistre un fallback (retry sur autre gateway)
     */
    public void recordFallback(GatewayType fromGateway, GatewayType toGateway, 
                               Country source, Country dest, String reason) {
        GatewayStats gStats = gatewayStats.get(fromGateway);
        if (gStats != null) {
            gStats.recordFallback();
        }

        String corridorKey = source.getIsoCode() + "->" + dest.getIsoCode();
        corridorStats.computeIfAbsent(corridorKey, k -> new CorridorStats(source, dest))
                .recordFallback(fromGateway, toGateway);

        log.info("Analytics: fallback recorded {} -> {} for corridor {}", fromGateway, toGateway, corridorKey);
    }

    /**
     * Enregistre une transaction bridge réussie
     */
    public void recordBridgeSuccess(Country source, Country dest, List<Country> bridgeCountries,
                                    Long amount, Long fee, long totalExecutionTimeMs, int hopCount) {
        totalTransactions.incrementAndGet();
        totalSuccessful.incrementAndGet();
        totalBridgeTransactions.incrementAndGet();
        successfulBridgeTransactions.incrementAndGet();
        totalVolume.addAndGet(amount);
        totalFees.addAndGet(fee);

        // Stats bridge spécifiques
        String bridgeKey = buildBridgeKey(source, dest, bridgeCountries);
        bridgeStats.computeIfAbsent(bridgeKey, k -> new BridgeStats(source, dest, bridgeCountries))
                .recordSuccess(amount, fee, totalExecutionTimeMs);

        // Stats corridor (marquer comme bridge)
        String corridorKey = source.getIsoCode() + "->" + dest.getIsoCode();
        corridorStats.computeIfAbsent(corridorKey, k -> new CorridorStats(source, dest))
                .recordBridgeSuccess(amount, fee);

        // Stats journalières
        dailyHistory.computeIfAbsent(LocalDate.now(), k -> new DailyStats())
                .recordBridgeSuccess(amount, fee);

        log.info("Analytics: bridge success recorded {} -> {} via {} hops, amount={}", 
                source, dest, hopCount, amount);
    }

    /**
     * Enregistre une transaction bridge échouée
     */
    public void recordBridgeFailure(Country source, Country dest, List<Country> bridgeCountries,
                                    Long amount, int failedLegNumber, String reason) {
        totalTransactions.incrementAndGet();
        totalFailed.incrementAndGet();
        totalBridgeTransactions.incrementAndGet();

        // Stats bridge spécifiques
        String bridgeKey = buildBridgeKey(source, dest, bridgeCountries);
        bridgeStats.computeIfAbsent(bridgeKey, k -> new BridgeStats(source, dest, bridgeCountries))
                .recordFailure(failedLegNumber, reason);

        // Stats corridor
        String corridorKey = source.getIsoCode() + "->" + dest.getIsoCode();
        corridorStats.computeIfAbsent(corridorKey, k -> new CorridorStats(source, dest))
                .recordBridgeFailure(reason);

        // Stats journalières
        dailyHistory.computeIfAbsent(LocalDate.now(), k -> new DailyStats())
                .recordFailure();

        log.warn("Analytics: bridge failure recorded {} -> {} at leg {}, reason={}", 
                source, dest, failedLegNumber, reason);
    }

    private String buildBridgeKey(Country source, Country dest, List<Country> bridges) {
        StringBuilder sb = new StringBuilder();
        sb.append(source.getIsoCode());
        for (Country bridge : bridges) {
            sb.append("->").append(bridge.getIsoCode());
        }
        sb.append("->").append(dest.getIsoCode());
        return sb.toString();
    }

    // === Récupération des métriques ===

    /**
     * Métriques globales
     */
    public GlobalMetrics getGlobalMetrics() {
        long total = totalTransactions.get();
        long successful = totalSuccessful.get();
        
        return GlobalMetrics.builder()
                .totalTransactions(total)
                .successfulTransactions(successful)
                .failedTransactions(totalFailed.get())
                .successRate(total > 0 ? (successful * 100.0 / total) : 0)
                .totalVolume(totalVolume.get())
                .totalFees(totalFees.get())
                .averageTransactionAmount(successful > 0 ? totalVolume.get() / successful : 0)
                .totalBridgeTransactions(totalBridgeTransactions.get())
                .successfulBridgeTransactions(successfulBridgeTransactions.get())
                .bridgeUsageRate(total > 0 ? (totalBridgeTransactions.get() * 100.0 / total) : 0)
                .build();
    }

    /**
     * Métriques par gateway
     */
    public GatewayMetrics getGatewayMetrics(GatewayType gateway) {
        GatewayStats stats = gatewayStats.get(gateway);
        if (stats == null) {
            return GatewayMetrics.empty(gateway);
        }
        return stats.toMetrics();
    }

    /**
     * Métriques de toutes les gateways
     */
    public Map<GatewayType, GatewayMetrics> getAllGatewayMetrics() {
        return gatewayStats.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toMetrics()));
    }

    /**
     * Métriques par corridor
     */
    public CorridorMetrics getCorridorMetrics(Country source, Country dest) {
        String key = source.getIsoCode() + "->" + dest.getIsoCode();
        CorridorStats stats = corridorStats.get(key);
        if (stats == null) {
            return CorridorMetrics.empty(source, dest);
        }
        return stats.toMetrics();
    }

    /**
     * Top corridors par volume
     */
    public List<CorridorMetrics> getTopCorridorsByVolume(int limit) {
        return corridorStats.values().stream()
                .map(CorridorStats::toMetrics)
                .sorted(Comparator.comparingLong(CorridorMetrics::getTotalVolume).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Métriques des routes bridge
     */
    public List<BridgeMetrics> getBridgeMetrics() {
        return bridgeStats.values().stream()
                .map(BridgeStats::toMetrics)
                .sorted(Comparator.comparingLong(BridgeMetrics::getTotalTransactions).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Top bridges par utilisation
     */
    public List<BridgeMetrics> getTopBridges(int limit) {
        return bridgeStats.values().stream()
                .map(BridgeStats::toMetrics)
                .sorted(Comparator.comparingLong(BridgeMetrics::getTotalTransactions).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Bridges problématiques (taux d'échec élevé)
     */
    public List<BridgeMetrics> getProblematicBridges(int limit) {
        return bridgeStats.values().stream()
                .map(BridgeStats::toMetrics)
                .filter(m -> m.getFailureRate() > 10 && m.getTotalTransactions() > 3)
                .sorted(Comparator.comparingDouble(BridgeMetrics::getFailureRate).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Corridors avec le plus d'échecs
     */
    public List<CorridorMetrics> getProblematicCorridors(int limit) {
        return corridorStats.values().stream()
                .map(CorridorStats::toMetrics)
                .filter(m -> m.getFailureRate() > 5) // Plus de 5% d'échecs
                .sorted(Comparator.comparingDouble(CorridorMetrics::getFailureRate).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Tendances sur les derniers jours
     */
    public List<DailyMetrics> getDailyTrends(int days) {
        LocalDate today = LocalDate.now();
        List<DailyMetrics> trends = new ArrayList<>();
        
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            DailyStats stats = dailyHistory.get(date);
            if (stats != null) {
                trends.add(stats.toMetrics(date));
            } else {
                trends.add(DailyMetrics.empty(date));
            }
        }
        
        return trends;
    }

    /**
     * Recommandations d'optimisation basées sur les données
     */
    public List<OptimizationRecommendation> getOptimizationRecommendations() {
        List<OptimizationRecommendation> recommendations = new ArrayList<>();

        // Analyser les gateways sous-performantes
        for (Map.Entry<GatewayType, GatewayStats> entry : gatewayStats.entrySet()) {
            GatewayMetrics metrics = entry.getValue().toMetrics();
            
            if (metrics.getSuccessRate() < 90 && metrics.getTotalTransactions() > 10) {
                recommendations.add(OptimizationRecommendation.builder()
                        .type(RecommendationType.GATEWAY_RELIABILITY)
                        .priority(Priority.HIGH)
                        .gateway(entry.getKey())
                        .message("Gateway " + entry.getKey() + " a un taux de succès de " + 
                                String.format("%.1f%%", metrics.getSuccessRate()) + 
                                ". Considérer réduire sa priorité.")
                        .suggestedAction("Réduire le poids de fiabilité ou blacklister temporairement")
                        .build());
            }

            if (metrics.getAverageResponseTimeMs() > 5000 && metrics.getTotalTransactions() > 10) {
                recommendations.add(OptimizationRecommendation.builder()
                        .type(RecommendationType.GATEWAY_SPEED)
                        .priority(Priority.MEDIUM)
                        .gateway(entry.getKey())
                        .message("Gateway " + entry.getKey() + " a un temps de réponse moyen de " + 
                                metrics.getAverageResponseTimeMs() + "ms")
                        .suggestedAction("Augmenter le poids de vitesse dans le scoring")
                        .build());
            }
        }

        // Analyser les corridors problématiques
        for (CorridorStats stats : corridorStats.values()) {
            CorridorMetrics metrics = stats.toMetrics();
            
            if (metrics.getFailureRate() > 10 && metrics.getTotalTransactions() > 5) {
                recommendations.add(OptimizationRecommendation.builder()
                        .type(RecommendationType.CORRIDOR_ISSUE)
                        .priority(Priority.HIGH)
                        .sourceCountry(metrics.getSourceCountry())
                        .destCountry(metrics.getDestCountry())
                        .message("Corridor " + metrics.getSourceCountry() + " -> " + metrics.getDestCountry() + 
                                " a un taux d'échec de " + String.format("%.1f%%", metrics.getFailureRate()))
                        .suggestedAction("Vérifier les routes disponibles et ajouter des alternatives")
                        .build());
            }
        }

        return recommendations;
    }

    // === Alertes ===

    public List<RoutingAlert> getActiveAlerts() {
        // Nettoyer les alertes expirées
        activeAlerts.removeIf(alert -> 
                alert.getCreatedAt().plus(1, ChronoUnit.HOURS).isBefore(Instant.now()));
        return new ArrayList<>(activeAlerts);
    }

    public void acknowledgeAlert(String alertId) {
        activeAlerts.removeIf(alert -> alert.getId().equals(alertId));
    }

    private void checkAndTriggerAlerts(GatewayType gateway, Country source, Country dest) {
        GatewayStats stats = gatewayStats.get(gateway);
        if (stats != null && stats.getConsecutiveFailures() >= 3) {
            String alertId = "GW-" + gateway + "-" + Instant.now().toEpochMilli();
            activeAlerts.add(RoutingAlert.builder()
                    .id(alertId)
                    .type(AlertType.GATEWAY_DEGRADED)
                    .severity(AlertSeverity.WARNING)
                    .gateway(gateway)
                    .message("Gateway " + gateway + " a " + stats.getConsecutiveFailures() + " échecs consécutifs")
                    .createdAt(Instant.now())
                    .build());
        }
    }

    // === Nettoyage périodique ===

    @Scheduled(cron = "0 0 2 * * *") // Tous les jours à 2h
    public void cleanupOldData() {
        LocalDate cutoff = LocalDate.now().minusDays(90);
        dailyHistory.entrySet().removeIf(entry -> entry.getKey().isBefore(cutoff));
        log.info("Analytics cleanup: removed data older than {}", cutoff);
    }

    // === Inner Classes ===

    private static class GatewayStats {
        private final GatewayType gateway;
        private final AtomicLong totalTransactions = new AtomicLong(0);
        private final AtomicLong successfulTransactions = new AtomicLong(0);
        private final AtomicLong failedTransactions = new AtomicLong(0);
        private final AtomicLong totalVolume = new AtomicLong(0);
        private final AtomicLong totalFees = new AtomicLong(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final AtomicLong fallbackCount = new AtomicLong(0);
        private volatile int consecutiveFailures = 0;
        private volatile String lastFailureReason;

        GatewayStats(GatewayType gateway) {
            this.gateway = gateway;
        }

        void recordSuccess(Long amount, Long fee, long responseTimeMs) {
            totalTransactions.incrementAndGet();
            successfulTransactions.incrementAndGet();
            totalVolume.addAndGet(amount);
            totalFees.addAndGet(fee);
            totalResponseTime.addAndGet(responseTimeMs);
            consecutiveFailures = 0;
        }

        void recordFailure(String reason) {
            totalTransactions.incrementAndGet();
            failedTransactions.incrementAndGet();
            consecutiveFailures++;
            lastFailureReason = reason;
        }

        void recordFallback() {
            fallbackCount.incrementAndGet();
        }

        int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        GatewayMetrics toMetrics() {
            long total = totalTransactions.get();
            long successful = successfulTransactions.get();
            
            return GatewayMetrics.builder()
                    .gateway(gateway)
                    .totalTransactions(total)
                    .successfulTransactions(successful)
                    .failedTransactions(failedTransactions.get())
                    .successRate(total > 0 ? (successful * 100.0 / total) : 100)
                    .totalVolume(totalVolume.get())
                    .totalFees(totalFees.get())
                    .averageResponseTimeMs(successful > 0 ? totalResponseTime.get() / successful : 0)
                    .fallbackCount(fallbackCount.get())
                    .consecutiveFailures(consecutiveFailures)
                    .lastFailureReason(lastFailureReason)
                    .build();
        }
    }

    private static class CorridorStats {
        private final Country source;
        private final Country dest;
        private final AtomicLong totalTransactions = new AtomicLong(0);
        private final AtomicLong successfulTransactions = new AtomicLong(0);
        private final AtomicLong failedTransactions = new AtomicLong(0);
        private final AtomicLong totalVolume = new AtomicLong(0);
        private final AtomicLong totalFees = new AtomicLong(0);
        private final Map<GatewayType, AtomicLong> gatewayUsage = new ConcurrentHashMap<>();
        private final AtomicLong fallbackCount = new AtomicLong(0);

        CorridorStats(Country source, Country dest) {
            this.source = source;
            this.dest = dest;
        }

        void recordSuccess(GatewayType gateway, Long amount, Long fee, long responseTimeMs) {
            totalTransactions.incrementAndGet();
            successfulTransactions.incrementAndGet();
            totalVolume.addAndGet(amount);
            totalFees.addAndGet(fee);
            gatewayUsage.computeIfAbsent(gateway, k -> new AtomicLong(0)).incrementAndGet();
        }

        void recordFailure(GatewayType gateway, String reason) {
            totalTransactions.incrementAndGet();
            failedTransactions.incrementAndGet();
        }

        void recordFallback(GatewayType from, GatewayType to) {
            fallbackCount.incrementAndGet();
        }

        void recordBridgeSuccess(Long amount, Long fee) {
            totalTransactions.incrementAndGet();
            successfulTransactions.incrementAndGet();
            totalVolume.addAndGet(amount);
            totalFees.addAndGet(fee);
        }

        void recordBridgeFailure(String reason) {
            totalTransactions.incrementAndGet();
            failedTransactions.incrementAndGet();
        }

        CorridorMetrics toMetrics() {
            long total = totalTransactions.get();
            long successful = successfulTransactions.get();
            long failed = failedTransactions.get();
            
            Map<GatewayType, Long> usage = gatewayUsage.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

            return CorridorMetrics.builder()
                    .sourceCountry(source)
                    .destCountry(dest)
                    .totalTransactions(total)
                    .successfulTransactions(successful)
                    .failedTransactions(failed)
                    .successRate(total > 0 ? (successful * 100.0 / total) : 100)
                    .failureRate(total > 0 ? (failed * 100.0 / total) : 0)
                    .totalVolume(totalVolume.get())
                    .totalFees(totalFees.get())
                    .gatewayUsage(usage)
                    .fallbackCount(fallbackCount.get())
                    .build();
        }
    }

    private static class DailyStats {
        private final AtomicLong transactions = new AtomicLong(0);
        private final AtomicLong successful = new AtomicLong(0);
        private final AtomicLong failed = new AtomicLong(0);
        private final AtomicLong volume = new AtomicLong(0);
        private final AtomicLong fees = new AtomicLong(0);

        void recordSuccess(Long amount, Long fee) {
            transactions.incrementAndGet();
            successful.incrementAndGet();
            volume.addAndGet(amount);
            fees.addAndGet(fee);
        }

        void recordBridgeSuccess(Long amount, Long fee) {
            transactions.incrementAndGet();
            successful.incrementAndGet();
            volume.addAndGet(amount);
            fees.addAndGet(fee);
        }

        void recordFailure() {
            transactions.incrementAndGet();
            failed.incrementAndGet();
        }

        DailyMetrics toMetrics(LocalDate date) {
            long total = transactions.get();
            long succ = successful.get();
            return DailyMetrics.builder()
                    .date(date)
                    .totalTransactions(total)
                    .successfulTransactions(succ)
                    .failedTransactions(failed.get())
                    .successRate(total > 0 ? (succ * 100.0 / total) : 100)
                    .totalVolume(volume.get())
                    .totalFees(fees.get())
                    .build();
        }
    }

    private static class BridgeStats {
        private final Country source;
        private final Country dest;
        private final List<Country> bridgeCountries;
        private final AtomicLong totalTransactions = new AtomicLong(0);
        private final AtomicLong successfulTransactions = new AtomicLong(0);
        private final AtomicLong failedTransactions = new AtomicLong(0);
        private final AtomicLong totalVolume = new AtomicLong(0);
        private final AtomicLong totalFees = new AtomicLong(0);
        private final AtomicLong totalExecutionTime = new AtomicLong(0);
        private final Map<Integer, AtomicLong> failuresByLeg = new ConcurrentHashMap<>();
        private volatile String lastFailureReason;

        BridgeStats(Country source, Country dest, List<Country> bridgeCountries) {
            this.source = source;
            this.dest = dest;
            this.bridgeCountries = new ArrayList<>(bridgeCountries);
        }

        void recordSuccess(Long amount, Long fee, long executionTimeMs) {
            totalTransactions.incrementAndGet();
            successfulTransactions.incrementAndGet();
            totalVolume.addAndGet(amount);
            totalFees.addAndGet(fee);
            totalExecutionTime.addAndGet(executionTimeMs);
        }

        void recordFailure(int failedLegNumber, String reason) {
            totalTransactions.incrementAndGet();
            failedTransactions.incrementAndGet();
            failuresByLeg.computeIfAbsent(failedLegNumber, k -> new AtomicLong(0)).incrementAndGet();
            lastFailureReason = reason;
        }

        BridgeMetrics toMetrics() {
            long total = totalTransactions.get();
            long successful = successfulTransactions.get();
            long failed = failedTransactions.get();

            Map<Integer, Long> legFailures = failuresByLeg.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

            return BridgeMetrics.builder()
                    .sourceCountry(source)
                    .destCountry(dest)
                    .bridgeCountries(new ArrayList<>(bridgeCountries))
                    .hopCount(bridgeCountries.size())
                    .routeDescription(buildRouteDescription())
                    .totalTransactions(total)
                    .successfulTransactions(successful)
                    .failedTransactions(failed)
                    .successRate(total > 0 ? (successful * 100.0 / total) : 100)
                    .failureRate(total > 0 ? (failed * 100.0 / total) : 0)
                    .totalVolume(totalVolume.get())
                    .totalFees(totalFees.get())
                    .averageExecutionTimeMs(successful > 0 ? totalExecutionTime.get() / successful : 0)
                    .failuresByLeg(legFailures)
                    .lastFailureReason(lastFailureReason)
                    .build();
        }

        private String buildRouteDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(source.getIsoCode());
            for (Country bridge : bridgeCountries) {
                sb.append(" → ").append(bridge.getIsoCode());
            }
            sb.append(" → ").append(dest.getIsoCode());
            return sb.toString();
        }
    }

    // === DTOs ===

    @lombok.Data
    @lombok.Builder
    public static class GlobalMetrics {
        private long totalTransactions;
        private long successfulTransactions;
        private long failedTransactions;
        private double successRate;
        private long totalVolume;
        private long totalFees;
        private long averageTransactionAmount;
        private long totalBridgeTransactions;
        private long successfulBridgeTransactions;
        private double bridgeUsageRate;
    }

    @lombok.Data
    @lombok.Builder
    public static class GatewayMetrics {
        private GatewayType gateway;
        private long totalTransactions;
        private long successfulTransactions;
        private long failedTransactions;
        private double successRate;
        private long totalVolume;
        private long totalFees;
        private long averageResponseTimeMs;
        private long fallbackCount;
        private int consecutiveFailures;
        private String lastFailureReason;

        public static GatewayMetrics empty(GatewayType gateway) {
            return GatewayMetrics.builder().gateway(gateway).successRate(100).build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class CorridorMetrics {
        private Country sourceCountry;
        private Country destCountry;
        private long totalTransactions;
        private long successfulTransactions;
        private long failedTransactions;
        private double successRate;
        private double failureRate;
        private long totalVolume;
        private long totalFees;
        private Map<GatewayType, Long> gatewayUsage;
        private long fallbackCount;

        public static CorridorMetrics empty(Country source, Country dest) {
            return CorridorMetrics.builder()
                    .sourceCountry(source)
                    .destCountry(dest)
                    .successRate(100)
                    .gatewayUsage(new HashMap<>())
                    .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class DailyMetrics {
        private LocalDate date;
        private long totalTransactions;
        private long successfulTransactions;
        private long failedTransactions;
        private double successRate;
        private long totalVolume;
        private long totalFees;

        public static DailyMetrics empty(LocalDate date) {
            return DailyMetrics.builder().date(date).successRate(100).build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class BridgeMetrics {
        private Country sourceCountry;
        private Country destCountry;
        private List<Country> bridgeCountries;
        private int hopCount;
        private String routeDescription;
        private long totalTransactions;
        private long successfulTransactions;
        private long failedTransactions;
        private double successRate;
        private double failureRate;
        private long totalVolume;
        private long totalFees;
        private long averageExecutionTimeMs;
        private Map<Integer, Long> failuresByLeg;
        private String lastFailureReason;
    }

    @lombok.Data
    @lombok.Builder
    public static class OptimizationRecommendation {
        private RecommendationType type;
        private Priority priority;
        private GatewayType gateway;
        private Country sourceCountry;
        private Country destCountry;
        private String message;
        private String suggestedAction;
    }

    public enum RecommendationType {
        GATEWAY_RELIABILITY, GATEWAY_SPEED, GATEWAY_COST, CORRIDOR_ISSUE, STOCK_LOW
    }

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    @lombok.Data
    @lombok.Builder
    public static class RoutingAlert {
        private String id;
        private AlertType type;
        private AlertSeverity severity;
        private GatewayType gateway;
        private Country sourceCountry;
        private Country destCountry;
        private String message;
        private Instant createdAt;
    }

    public enum AlertType {
        GATEWAY_DOWN, GATEWAY_DEGRADED, CORRIDOR_BLOCKED, STOCK_DEPLETED, HIGH_FAILURE_RATE
    }

    public enum AlertSeverity {
        INFO, WARNING, ERROR, CRITICAL
    }
}
