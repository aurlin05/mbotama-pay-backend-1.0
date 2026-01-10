package com.mbotamapay.service.orchestration;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration dynamique du routage
 * Permet d'ajuster les règles en temps réel sans redéploiement
 */
@Component
@Slf4j
public class DynamicRoutingConfig {

    // Configurations par défaut modifiables à runtime
    private final Map<String, Object> config = new ConcurrentHashMap<>();

    // Règles de routage temporaires (promotions, maintenance, etc.)
    private final Map<String, RoutingRule> temporaryRules = new ConcurrentHashMap<>();

    // Blacklist temporaire de gateways
    private final Set<GatewayType> blacklistedGateways = ConcurrentHashMap.newKeySet();

    // Préférences par corridor
    private final Map<String, CorridorPreference> corridorPreferences = new ConcurrentHashMap<>();

    // Plages horaires avec règles spécifiques
    private final List<TimeBasedRule> timeBasedRules = Collections.synchronizedList(new ArrayList<>());

    public DynamicRoutingConfig() {
        initializeDefaults();
    }

    private void initializeDefaults() {
        // Poids des critères de scoring
        config.put("weight.cost", 30);
        config.put("weight.reliability", 30);
        config.put("weight.speed", 15);
        config.put("weight.stock", 15);
        config.put("weight.operator", 10);

        // Seuils
        config.put("min.score.threshold", 30);
        config.put("split.threshold", 5000000L);
        config.put("max.retries", 3);

        // Comportement
        config.put("prefer.same.gateway", true);
        config.put("prefer.direct.route", true);
        config.put("enable.split.routing", true);
        config.put("enable.fallback", true);
    }

    // === Getters de configuration ===

    public int getWeightCost() {
        return (int) config.getOrDefault("weight.cost", 30);
    }

    public int getWeightReliability() {
        return (int) config.getOrDefault("weight.reliability", 30);
    }

    public int getWeightSpeed() {
        return (int) config.getOrDefault("weight.speed", 15);
    }

    public int getWeightStock() {
        return (int) config.getOrDefault("weight.stock", 15);
    }

    public int getWeightOperator() {
        return (int) config.getOrDefault("weight.operator", 10);
    }

    public int getMinScoreThreshold() {
        return (int) config.getOrDefault("min.score.threshold", 30);
    }

    public long getSplitThreshold() {
        return (long) config.getOrDefault("split.threshold", 5000000L);
    }

    public int getMaxRetries() {
        return (int) config.getOrDefault("max.retries", 3);
    }

    public boolean isPreferSameGateway() {
        return (boolean) config.getOrDefault("prefer.same.gateway", true);
    }

    public boolean isSplitRoutingEnabled() {
        return (boolean) config.getOrDefault("enable.split.routing", true);
    }

    public boolean isFallbackEnabled() {
        return (boolean) config.getOrDefault("enable.fallback", true);
    }

    // === Setters de configuration (pour admin) ===

    public void setConfig(String key, Object value) {
        config.put(key, value);
        log.info("Config updated: {} = {}", key, value);
    }

    public void setWeights(int cost, int reliability, int speed, int stock, int operator) {
        if (cost + reliability + speed + stock + operator != 100) {
            throw new IllegalArgumentException("Les poids doivent totaliser 100");
        }
        config.put("weight.cost", cost);
        config.put("weight.reliability", reliability);
        config.put("weight.speed", speed);
        config.put("weight.stock", stock);
        config.put("weight.operator", operator);
        log.info("Scoring weights updated: cost={}, rel={}, speed={}, stock={}, op={}",
                cost, reliability, speed, stock, operator);
    }

    // === Gestion des blacklists ===

    public void blacklistGateway(GatewayType gateway, String reason) {
        blacklistedGateways.add(gateway);
        log.warn("Gateway {} blacklisted: {}", gateway, reason);
    }

    public void unblacklistGateway(GatewayType gateway) {
        blacklistedGateways.remove(gateway);
        log.info("Gateway {} removed from blacklist", gateway);
    }

    public boolean isGatewayBlacklisted(GatewayType gateway) {
        return blacklistedGateways.contains(gateway);
    }

    public Set<GatewayType> getBlacklistedGateways() {
        return Collections.unmodifiableSet(blacklistedGateways);
    }

    // === Préférences par corridor ===

    public void setCorridorPreference(Country source, Country dest, CorridorPreference preference) {
        String key = source.getIsoCode() + "->" + dest.getIsoCode();
        corridorPreferences.put(key, preference);
        log.info("Corridor preference set for {}: {}", key, preference);
    }

    public Optional<CorridorPreference> getCorridorPreference(Country source, Country dest) {
        String key = source.getIsoCode() + "->" + dest.getIsoCode();
        return Optional.ofNullable(corridorPreferences.get(key));
    }

    public void removeCorridorPreference(Country source, Country dest) {
        String key = source.getIsoCode() + "->" + dest.getIsoCode();
        corridorPreferences.remove(key);
        log.info("Corridor preference removed for {}", key);
    }

    // === Règles temporaires ===

    public void addTemporaryRule(String ruleId, RoutingRule rule) {
        temporaryRules.put(ruleId, rule);
        log.info("Temporary rule added: {} - {}", ruleId, rule);
    }

    public void removeTemporaryRule(String ruleId) {
        temporaryRules.remove(ruleId);
        log.info("Temporary rule removed: {}", ruleId);
    }

    public List<RoutingRule> getActiveTemporaryRules() {
        LocalDateTime now = LocalDateTime.now();
        return temporaryRules.values().stream()
                .filter(rule -> rule.isActiveAt(now))
                .toList();
    }

    // === Règles basées sur l'heure ===

    public void addTimeBasedRule(TimeBasedRule rule) {
        timeBasedRules.add(rule);
        log.info("Time-based rule added: {}", rule);
    }

    public void clearTimeBasedRules() {
        timeBasedRules.clear();
        log.info("All time-based rules cleared");
    }

    public Optional<TimeBasedRule> getActiveTimeBasedRule() {
        LocalDateTime now = LocalDateTime.now();
        return timeBasedRules.stream()
                .filter(rule -> rule.isActiveAt(now))
                .findFirst();
    }

    // === Application des règles ===

    /**
     * Applique toutes les règles dynamiques pour ajuster le score d'une route
     */
    public int applyRulesToScore(int baseScore, GatewayType gateway, Country source, Country dest, Long amount) {
        int adjustedScore = baseScore;

        // 1. Vérifier blacklist
        if (isGatewayBlacklisted(gateway)) {
            return 0;
        }

        // 2. Appliquer préférence corridor
        Optional<CorridorPreference> corridorPref = getCorridorPreference(source, dest);
        if (corridorPref.isPresent()) {
            CorridorPreference pref = corridorPref.get();
            if (pref.getPreferredGateway() == gateway) {
                adjustedScore += pref.getBonus();
            } else if (pref.getAvoidGateway() == gateway) {
                adjustedScore -= pref.getPenalty();
            }
        }

        // 3. Appliquer règles temporaires
        for (RoutingRule rule : getActiveTemporaryRules()) {
            if (rule.appliesTo(gateway, source, dest, amount)) {
                adjustedScore = rule.adjustScore(adjustedScore);
            }
        }

        // 4. Appliquer règles horaires
        Optional<TimeBasedRule> timeRule = getActiveTimeBasedRule();
        if (timeRule.isPresent()) {
            adjustedScore = timeRule.get().adjustScore(adjustedScore, gateway);
        }

        return Math.max(0, Math.min(100, adjustedScore));
    }

    /**
     * Retourne toute la configuration actuelle (pour monitoring/debug)
     */
    public Map<String, Object> getAllConfig() {
        Map<String, Object> all = new HashMap<>(config);
        all.put("blacklistedGateways", new ArrayList<>(blacklistedGateways));
        all.put("corridorPreferences", new HashMap<>(corridorPreferences));
        all.put("temporaryRulesCount", temporaryRules.size());
        all.put("timeBasedRulesCount", timeBasedRules.size());
        return all;
    }

    // === Inner Classes ===

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CorridorPreference {
        private GatewayType preferredGateway;
        private GatewayType avoidGateway;
        private int bonus;      // Points ajoutés pour la gateway préférée
        private int penalty;    // Points retirés pour la gateway à éviter
        private BigDecimal maxFeeOverride;  // Override des frais max acceptés
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RoutingRule {
        private String name;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Set<GatewayType> targetGateways;
        private Set<Country> targetCountries;
        private Long minAmount;
        private Long maxAmount;
        private int scoreAdjustment;    // Positif = bonus, négatif = pénalité
        private boolean forceGateway;   // Si true, ignore les autres gateways

        public boolean isActiveAt(LocalDateTime time) {
            if (startTime != null && time.isBefore(startTime)) return false;
            if (endTime != null && time.isAfter(endTime)) return false;
            return true;
        }

        public boolean appliesTo(GatewayType gateway, Country source, Country dest, Long amount) {
            if (targetGateways != null && !targetGateways.isEmpty() && !targetGateways.contains(gateway)) {
                return false;
            }
            if (targetCountries != null && !targetCountries.isEmpty() && 
                !targetCountries.contains(source) && !targetCountries.contains(dest)) {
                return false;
            }
            if (minAmount != null && amount < minAmount) return false;
            if (maxAmount != null && amount > maxAmount) return false;
            return true;
        }

        public int adjustScore(int score) {
            return score + scoreAdjustment;
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TimeBasedRule {
        private String name;
        private Set<DayOfWeek> activeDays;
        private LocalTime startTime;
        private LocalTime endTime;
        private Map<GatewayType, Integer> gatewayAdjustments;

        public boolean isActiveAt(LocalDateTime dateTime) {
            if (activeDays != null && !activeDays.isEmpty() && !activeDays.contains(dateTime.getDayOfWeek())) {
                return false;
            }
            LocalTime time = dateTime.toLocalTime();
            if (startTime != null && time.isBefore(startTime)) return false;
            if (endTime != null && time.isAfter(endTime)) return false;
            return true;
        }

        public int adjustScore(int score, GatewayType gateway) {
            if (gatewayAdjustments != null && gatewayAdjustments.containsKey(gateway)) {
                return score + gatewayAdjustments.get(gateway);
            }
            return score;
        }
    }
}
