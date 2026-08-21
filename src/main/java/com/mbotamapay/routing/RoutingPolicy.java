package com.mbotamapay.routing;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 : surcouche d'exploitation, appliquée <em>après</em> le score.
 *
 * <p>
 * La version précédente prévoyait {@code applyRulesToScore()}, qui ajoutait ou
 * retirait des points au score — et n'était de toute façon appelée par aucun
 * composant de routage. Rebrancher ce mécanisme tel quel aurait été une erreur :
 * un opérateur qui écrit « éviter CinetPay sur SN→CI » veut un effet
 * déterministe et vérifiable, pas ±15 points dilués dans une moyenne pondérée
 * dont l'effet dépend des autres critères du moment.
 *
 * <p>
 * La politique agit donc par opérations explicites et traçables :
 * <ul>
 * <li><strong>suspension</strong> — traitée en amont, comme porte d'éligibilité</li>
 * <li><strong>forçage</strong> — sélectionne directement, court-circuite le classement</li>
 * <li><strong>préférence</strong> — réordonne, sans toucher aux scores</li>
 * </ul>
 * Chaque intervention est inscrite dans la décision persistée.
 */
@Component
@Slf4j
public class RoutingPolicy {

    private final Set<GatewayType> suspended = ConcurrentHashMap.newKeySet();
    private final Map<String, CorridorPreference> preferences = new ConcurrentHashMap<>();
    private final List<TimeWindow> timeWindows = Collections.synchronizedList(new ArrayList<>());

    // === Suspension (consultée par la porte d'éligibilité) ===

    public void suspend(GatewayType gateway, String reason) {
        suspended.add(gateway);
        log.warn("Gateway {} suspended: {}", gateway, reason);
    }

    public void resume(GatewayType gateway) {
        suspended.remove(gateway);
        log.info("Gateway {} resumed", gateway);
    }

    public boolean isSuspended(GatewayType gateway) {
        return suspended.contains(gateway);
    }

    public Set<GatewayType> suspendedGateways() {
        return Set.copyOf(suspended);
    }

    // === Préférences par corridor ===

    public void setPreference(Country source, Country dest, CorridorPreference preference) {
        preferences.put(corridorKey(source, dest), preference);
        log.info("Corridor preference set for {}: {}", corridorKey(source, dest), preference);
    }

    public void clearPreference(Country source, Country dest) {
        preferences.remove(corridorKey(source, dest));
    }

    public Optional<CorridorPreference> preference(Country source, Country dest) {
        return Optional.ofNullable(preferences.get(corridorKey(source, dest)));
    }

    public Map<String, CorridorPreference> allPreferences() {
        return Map.copyOf(preferences);
    }

    // === Fenêtres horaires ===

    public void addTimeWindow(TimeWindow window) {
        timeWindows.add(window);
        log.info("Routing time window added: {}", window.name());
    }

    public void clearTimeWindows() {
        timeWindows.clear();
    }

    public List<TimeWindow> activeWindows(LocalDateTime at) {
        synchronized (timeWindows) {
            return timeWindows.stream().filter(w -> w.isActiveAt(at)).toList();
        }
    }

    // === Application ===

    /**
     * Réordonne un classement déjà établi. Ne modifie jamais un score : les scores
     * restent la trace de l'arbitrage technique, les dérogations sont enregistrées
     * à part.
     */
    public Outcome apply(List<ScoredRoute> ranked, RoutingContext request, LocalDateTime now) {
        if (ranked.isEmpty()) {
            return new Outcome(ranked, List.of());
        }

        List<String> overrides = new ArrayList<>();
        List<ScoredRoute> result = new ArrayList<>(ranked);

        Optional<CorridorPreference> pref = preference(request.sourceCountry(), request.destCountry());

        // 1. Forçage : si la passerelle imposée est encore dans les candidats
        // éligibles, elle passe en tête. Sinon on l'indique et on garde le
        // classement — un forçage ne doit jamais ressusciter une route écartée.
        if (pref.isPresent() && pref.get().forcedGateway() != null) {
            GatewayType forced = pref.get().forcedGateway();
            Optional<ScoredRoute> match = result.stream()
                    .filter(r -> r.gateway() == forced)
                    .findFirst();
            if (match.isPresent()) {
                result.remove(match.get());
                result.add(0, match.get());
                overrides.add("forçage " + forced.getDisplayName());
                return new Outcome(List.copyOf(result), List.copyOf(overrides));
            }
            overrides.add("forçage " + forced.getDisplayName() + " ignoré (route non éligible)");
        }

        // 2. Préférence / évitement : simple réordonnancement.
        if (pref.isPresent()) {
            CorridorPreference p = pref.get();
            Comparator<ScoredRoute> comparator = Comparator
                    .comparingInt((ScoredRoute r) -> r.gateway() == p.preferredGateway() ? 0 : 1)
                    .thenComparingInt(r -> r.gateway() == p.avoidGateway() ? 1 : 0);
            result.sort(comparator.thenComparing(Comparator.naturalOrder()));
            if (p.preferredGateway() != null) {
                overrides.add("préférence corridor " + p.preferredGateway().getDisplayName());
            }
            if (p.avoidGateway() != null) {
                overrides.add("évitement " + p.avoidGateway().getDisplayName());
            }
        }

        // 3. Fenêtres horaires : dépriorisation d'une passerelle sur un créneau.
        for (TimeWindow window : activeWindows(now)) {
            if (window.deprioritised() != null) {
                result.sort(Comparator
                        .comparingInt((ScoredRoute r) -> window.deprioritised().contains(r.gateway()) ? 1 : 0)
                        .thenComparing(Comparator.naturalOrder()));
                overrides.add("créneau « " + window.name() + " »");
            }
        }

        return new Outcome(List.copyOf(result), List.copyOf(overrides));
    }

    private String corridorKey(Country source, Country dest) {
        return source.getIsoCode() + "-" + dest.getIsoCode();
    }

    /** Classement final plus la liste des dérogations appliquées. */
    public record Outcome(List<ScoredRoute> ranked, List<String> overrides) {
    }

    @Builder
    public record CorridorPreference(
            GatewayType preferredGateway,
            GatewayType avoidGateway,
            GatewayType forcedGateway,
            String reason) {
    }

    @Builder
    public record TimeWindow(
            String name,
            Set<DayOfWeek> days,
            LocalTime from,
            LocalTime to,
            Set<GatewayType> deprioritised) {

        public boolean isActiveAt(LocalDateTime at) {
            if (days != null && !days.isEmpty() && !days.contains(at.getDayOfWeek())) {
                return false;
            }
            LocalTime time = at.toLocalTime();
            if (from != null && time.isBefore(from)) {
                return false;
            }
            return to == null || !time.isAfter(to);
        }
    }
}
