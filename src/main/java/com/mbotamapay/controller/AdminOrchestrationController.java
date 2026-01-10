package com.mbotamapay.controller;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.service.orchestration.*;
import com.mbotamapay.service.orchestration.DynamicRoutingConfig.*;
import com.mbotamapay.service.orchestration.GatewayHealthMonitor.GatewayMetrics;
import com.mbotamapay.service.orchestration.RoutingAnalytics.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * API d'administration pour l'orchestration des paiements
 * Permet de monitorer, configurer et optimiser le routage en temps réel
 */
@RestController
@RequestMapping("/api/admin/orchestration")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrchestrationController {

    private final GatewayHealthMonitor healthMonitor;
    private final DynamicRoutingConfig routingConfig;
    private final RoutingAnalytics analytics;
    private final SmartPaymentOrchestrator orchestrator;

    // ==================== HEALTH & MONITORING ====================

    /**
     * Dashboard complet de l'orchestration
     */
    @GetMapping("/dashboard")
    public ResponseEntity<OrchestrationDashboard> getDashboard() {
        return ResponseEntity.ok(OrchestrationDashboard.builder()
                .globalMetrics(analytics.getGlobalMetrics())
                .gatewayHealth(healthMonitor.getAllMetrics())
                .gatewayAnalytics(analytics.getAllGatewayMetrics())
                .topCorridors(analytics.getTopCorridorsByVolume(10))
                .problematicCorridors(analytics.getProblematicCorridors(5))
                .dailyTrends(analytics.getDailyTrends(7))
                .activeAlerts(analytics.getActiveAlerts())
                .recommendations(analytics.getOptimizationRecommendations())
                .currentConfig(routingConfig.getAllConfig())
                .build());
    }

    /**
     * Santé de toutes les gateways
     */
    @GetMapping("/health")
    public ResponseEntity<Map<GatewayType, GatewayMetrics>> getGatewayHealth() {
        return ResponseEntity.ok(healthMonitor.getAllMetrics());
    }

    /**
     * Santé d'une gateway spécifique
     */
    @GetMapping("/health/{gateway}")
    public ResponseEntity<GatewayMetrics> getGatewayHealth(@PathVariable GatewayType gateway) {
        return ResponseEntity.ok(healthMonitor.getMetrics(gateway));
    }

    /**
     * Reset manuel d'une gateway (réouvre le circuit breaker)
     */
    @PostMapping("/health/{gateway}/reset")
    public ResponseEntity<Map<String, String>> resetGateway(@PathVariable GatewayType gateway) {
        healthMonitor.resetGateway(gateway);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Gateway " + gateway + " reset successfully"
        ));
    }

    // ==================== ANALYTICS ====================

    /**
     * Métriques globales
     */
    @GetMapping("/analytics/global")
    public ResponseEntity<GlobalMetrics> getGlobalMetrics() {
        return ResponseEntity.ok(analytics.getGlobalMetrics());
    }

    /**
     * Métriques par gateway
     */
    @GetMapping("/analytics/gateway/{gateway}")
    public ResponseEntity<RoutingAnalytics.GatewayMetrics> getGatewayAnalytics(
            @PathVariable GatewayType gateway) {
        return ResponseEntity.ok(analytics.getGatewayMetrics(gateway));
    }

    /**
     * Métriques par corridor
     */
    @GetMapping("/analytics/corridor/{source}/{dest}")
    public ResponseEntity<CorridorMetrics> getCorridorMetrics(
            @PathVariable String source, @PathVariable String dest) {
        Country sourceCountry = Country.fromIsoCode(source)
                .orElseThrow(() -> new IllegalArgumentException("Invalid source country: " + source));
        Country destCountry = Country.fromIsoCode(dest)
                .orElseThrow(() -> new IllegalArgumentException("Invalid dest country: " + dest));
        return ResponseEntity.ok(analytics.getCorridorMetrics(sourceCountry, destCountry));
    }

    /**
     * Top corridors par volume
     */
    @GetMapping("/analytics/corridors/top")
    public ResponseEntity<List<CorridorMetrics>> getTopCorridors(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(analytics.getTopCorridorsByVolume(limit));
    }

    /**
     * Corridors problématiques
     */
    @GetMapping("/analytics/corridors/problematic")
    public ResponseEntity<List<CorridorMetrics>> getProblematicCorridors(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(analytics.getProblematicCorridors(limit));
    }

    /**
     * Tendances journalières
     */
    @GetMapping("/analytics/trends")
    public ResponseEntity<List<DailyMetrics>> getDailyTrends(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(analytics.getDailyTrends(days));
    }

    /**
     * Recommandations d'optimisation
     */
    @GetMapping("/analytics/recommendations")
    public ResponseEntity<List<OptimizationRecommendation>> getRecommendations() {
        return ResponseEntity.ok(analytics.getOptimizationRecommendations());
    }

    // ==================== CONFIGURATION ====================

    /**
     * Configuration actuelle
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(routingConfig.getAllConfig());
    }

    /**
     * Modifier les poids de scoring
     */
    @PutMapping("/config/weights")
    public ResponseEntity<Map<String, String>> updateWeights(@RequestBody WeightsRequest request) {
        routingConfig.setWeights(
                request.getCost(),
                request.getReliability(),
                request.getSpeed(),
                request.getStock(),
                request.getOperator()
        );
        return ResponseEntity.ok(Map.of("status", "success", "message", "Weights updated"));
    }

    /**
     * Modifier une configuration spécifique
     */
    @PutMapping("/config/{key}")
    public ResponseEntity<Map<String, String>> updateConfig(
            @PathVariable String key, @RequestBody ConfigValueRequest request) {
        routingConfig.setConfig(key, request.getValue());
        return ResponseEntity.ok(Map.of("status", "success", "message", "Config " + key + " updated"));
    }

    // ==================== BLACKLIST ====================

    /**
     * Liste des gateways blacklistées
     */
    @GetMapping("/blacklist")
    public ResponseEntity<Set<GatewayType>> getBlacklist() {
        return ResponseEntity.ok(routingConfig.getBlacklistedGateways());
    }

    /**
     * Blacklister une gateway
     */
    @PostMapping("/blacklist/{gateway}")
    public ResponseEntity<Map<String, String>> blacklistGateway(
            @PathVariable GatewayType gateway, @RequestBody BlacklistRequest request) {
        routingConfig.blacklistGateway(gateway, request.getReason());
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Gateway " + gateway + " blacklisted"
        ));
    }

    /**
     * Retirer une gateway de la blacklist
     */
    @DeleteMapping("/blacklist/{gateway}")
    public ResponseEntity<Map<String, String>> unblacklistGateway(@PathVariable GatewayType gateway) {
        routingConfig.unblacklistGateway(gateway);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Gateway " + gateway + " removed from blacklist"
        ));
    }

    // ==================== CORRIDOR PREFERENCES ====================

    /**
     * Définir une préférence de corridor
     */
    @PostMapping("/corridors/{source}/{dest}/preference")
    public ResponseEntity<Map<String, String>> setCorridorPreference(
            @PathVariable String source,
            @PathVariable String dest,
            @RequestBody CorridorPreferenceRequest request) {
        
        Country sourceCountry = Country.fromIsoCode(source)
                .orElseThrow(() -> new IllegalArgumentException("Invalid source country"));
        Country destCountry = Country.fromIsoCode(dest)
                .orElseThrow(() -> new IllegalArgumentException("Invalid dest country"));

        CorridorPreference preference = CorridorPreference.builder()
                .preferredGateway(request.getPreferredGateway())
                .avoidGateway(request.getAvoidGateway())
                .bonus(request.getBonus())
                .penalty(request.getPenalty())
                .build();

        routingConfig.setCorridorPreference(sourceCountry, destCountry, preference);
        
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Corridor preference set for " + source + " -> " + dest
        ));
    }

    /**
     * Supprimer une préférence de corridor
     */
    @DeleteMapping("/corridors/{source}/{dest}/preference")
    public ResponseEntity<Map<String, String>> removeCorridorPreference(
            @PathVariable String source, @PathVariable String dest) {
        
        Country sourceCountry = Country.fromIsoCode(source)
                .orElseThrow(() -> new IllegalArgumentException("Invalid source country"));
        Country destCountry = Country.fromIsoCode(dest)
                .orElseThrow(() -> new IllegalArgumentException("Invalid dest country"));

        routingConfig.removeCorridorPreference(sourceCountry, destCountry);
        
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Corridor preference removed"
        ));
    }

    // ==================== TEMPORARY RULES ====================

    /**
     * Ajouter une règle temporaire
     */
    @PostMapping("/rules/temporary")
    public ResponseEntity<Map<String, String>> addTemporaryRule(@RequestBody TemporaryRuleRequest request) {
        String ruleId = "RULE-" + System.currentTimeMillis();
        
        RoutingRule rule = RoutingRule.builder()
                .name(request.getName())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .targetGateways(request.getTargetGateways())
                .targetCountries(request.getTargetCountries())
                .minAmount(request.getMinAmount())
                .maxAmount(request.getMaxAmount())
                .scoreAdjustment(request.getScoreAdjustment())
                .forceGateway(request.isForceGateway())
                .build();

        routingConfig.addTemporaryRule(ruleId, rule);
        
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "ruleId", ruleId,
                "message", "Temporary rule added"
        ));
    }

    /**
     * Supprimer une règle temporaire
     */
    @DeleteMapping("/rules/temporary/{ruleId}")
    public ResponseEntity<Map<String, String>> removeTemporaryRule(@PathVariable String ruleId) {
        routingConfig.removeTemporaryRule(ruleId);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Rule removed"));
    }

    // ==================== TIME-BASED RULES ====================

    /**
     * Ajouter une règle basée sur l'heure
     */
    @PostMapping("/rules/time-based")
    public ResponseEntity<Map<String, String>> addTimeBasedRule(@RequestBody TimeBasedRuleRequest request) {
        TimeBasedRule rule = TimeBasedRule.builder()
                .name(request.getName())
                .activeDays(request.getActiveDays())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .gatewayAdjustments(request.getGatewayAdjustments())
                .build();

        routingConfig.addTimeBasedRule(rule);
        
        return ResponseEntity.ok(Map.of("status", "success", "message", "Time-based rule added"));
    }

    /**
     * Supprimer toutes les règles basées sur l'heure
     */
    @DeleteMapping("/rules/time-based")
    public ResponseEntity<Map<String, String>> clearTimeBasedRules() {
        routingConfig.clearTimeBasedRules();
        return ResponseEntity.ok(Map.of("status", "success", "message", "All time-based rules cleared"));
    }

    // ==================== ALERTS ====================

    /**
     * Alertes actives
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<RoutingAlert>> getActiveAlerts() {
        return ResponseEntity.ok(analytics.getActiveAlerts());
    }

    /**
     * Acquitter une alerte
     */
    @PostMapping("/alerts/{alertId}/acknowledge")
    public ResponseEntity<Map<String, String>> acknowledgeAlert(@PathVariable String alertId) {
        analytics.acknowledgeAlert(alertId);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Alert acknowledged"));
    }

    // ==================== SIMULATION ====================

    /**
     * Simuler un routage sans exécuter
     */
    @PostMapping("/simulate")
    public ResponseEntity<SmartPaymentOrchestrator.OrchestrationResult> simulateRouting(
            @RequestBody SimulationRequest request) {
        
        SmartPaymentOrchestrator.OrchestrationRequest orchRequest = 
                SmartPaymentOrchestrator.OrchestrationRequest.builder()
                        .senderPhone(request.getSenderPhone())
                        .recipientPhone(request.getRecipientPhone())
                        .amount(request.getAmount())
                        .build();

        return ResponseEntity.ok(orchestrator.orchestrate(orchRequest));
    }

    // ==================== REQUEST DTOs ====================

    @lombok.Data
    public static class WeightsRequest {
        private int cost;
        private int reliability;
        private int speed;
        private int stock;
        private int operator;
    }

    @lombok.Data
    public static class ConfigValueRequest {
        private Object value;
    }

    @lombok.Data
    public static class BlacklistRequest {
        private String reason;
    }

    @lombok.Data
    public static class CorridorPreferenceRequest {
        private GatewayType preferredGateway;
        private GatewayType avoidGateway;
        private int bonus;
        private int penalty;
    }

    @lombok.Data
    public static class TemporaryRuleRequest {
        private String name;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Set<GatewayType> targetGateways;
        private Set<Country> targetCountries;
        private Long minAmount;
        private Long maxAmount;
        private int scoreAdjustment;
        private boolean forceGateway;
    }

    @lombok.Data
    public static class TimeBasedRuleRequest {
        private String name;
        private Set<DayOfWeek> activeDays;
        private LocalTime startTime;
        private LocalTime endTime;
        private Map<GatewayType, Integer> gatewayAdjustments;
    }

    @lombok.Data
    public static class SimulationRequest {
        private String senderPhone;
        private String recipientPhone;
        private Long amount;
    }

    @lombok.Data
    @lombok.Builder
    public static class OrchestrationDashboard {
        private GlobalMetrics globalMetrics;
        private Map<GatewayType, GatewayHealthMonitor.GatewayMetrics> gatewayHealth;
        private Map<GatewayType, RoutingAnalytics.GatewayMetrics> gatewayAnalytics;
        private List<CorridorMetrics> topCorridors;
        private List<CorridorMetrics> problematicCorridors;
        private List<DailyMetrics> dailyTrends;
        private List<RoutingAlert> activeAlerts;
        private List<OptimizationRecommendation> recommendations;
        private Map<String, Object> currentConfig;
    }
}
