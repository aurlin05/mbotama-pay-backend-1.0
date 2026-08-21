package com.mbotamapay.routing;

import com.mbotamapay.entity.enums.GatewayType;
import lombok.Builder;

import java.util.List;
import java.util.Optional;

/**
 * Le choix de route, sous forme d'objet conservable.
 *
 * <p>
 * L'ancien moteur construisait une chaîne de caractères par concaténation
 * ({@code "Strategy: ... | Score: ... | Temps: ...ms"}) et la glissait dans un
 * champ de réponse. Pour un service qui déplace de l'argent, le choix de route
 * est une pièce d'audit : il faut pouvoir répondre, des mois plus tard, à
 * « pourquoi ce transfert est parti par cette passerelle, à ce prix ».
 *
 * <p>
 * La décision porte donc l'ensemble du raisonnement : candidats évalués, routes
 * écartées avec leur motif, scores détaillés par critère, dérogations
 * d'exploitation appliquées, et l'ordre de repli retenu.
 */
@Builder
public record RoutingDecision(
        String corridor,
        long amount,
        String sourceCurrency,
        String destCurrency,
        Outcome outcome,
        List<ScoredRoute> ranked,
        List<Eligibility.Rejection> rejected,
        List<String> policyOverrides,
        BridgeRouter.BridgeRoute bridge,
        long decisionTimeMs) {

    public enum Outcome {
        /** Une route directe éligible a été retenue. */
        DIRECT,
        /** Aucune route directe, un pont existe mais n'est pas exécutable. */
        BRIDGE_NOT_EXECUTABLE,
        /** Aucune route directe, un pont exécutable a été retenu. */
        BRIDGE,
        /** Rien n'est possible. Les motifs sont dans {@link #rejected}. */
        NO_ROUTE
    }

    public boolean isExecutable() {
        return outcome == Outcome.DIRECT || outcome == Outcome.BRIDGE;
    }

    /** Route élue, s'il y en a une. */
    public Optional<ScoredRoute> selected() {
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
    }

    /** Passerelles à tenter, dans l'ordre : la première, puis les replis. */
    public List<GatewayType> fallbackOrder() {
        return ranked.stream().map(ScoredRoute::gateway).distinct().toList();
    }

    /**
     * Explication lisible, destinée aux journaux et aux messages d'erreur. Quand
     * rien n'est possible, elle énumère les motifs plutôt que d'annoncer un score
     * insuffisant.
     */
    public String explain() {
        return switch (outcome) {
            case DIRECT -> selected()
                    .map(r -> String.format("%s — score %d (coût %d, marge %d, fiabilité %d, latence %d) sur %d observations",
                            r.gateway().getDisplayName(), r.totalScore(), r.costScore(),
                            r.marginScore(), r.reliabilityScore(), r.latencyScore(), r.observations()))
                    .orElse("route directe");
            case BRIDGE -> "pont " + bridge.describe() + " (" + bridge.totalFeePercent() + "%)";
            case BRIDGE_NOT_EXECUTABLE -> "pont " + bridge.describe()
                    + " identifié mais non exécutable : " + String.join(", ", bridge.blockers());
            case NO_ROUTE -> "aucune route pour " + corridor + " — "
                    + new Eligibility.Result(List.of(), rejected).explain();
        };
    }
}
