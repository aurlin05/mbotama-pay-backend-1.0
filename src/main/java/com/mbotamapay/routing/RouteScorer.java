package com.mbotamapay.routing;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.routing.Eligibility.Candidate;
import com.mbotamapay.service.FeeCalculator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 : l'arbitrage.
 *
 * <p>
 * Le score ne compare plus que des routes <strong>toutes exécutables</strong>.
 * Support opérateur, liquidité et disponibilité sont sortis du calcul : ce sont
 * des portes. Ce qui reste est réellement comparable.
 *
 * <p>
 * Deux changements de fond par rapport à la version précédente :
 *
 * <ul>
 * <li><strong>Le coût est mesuré en argent, pas en pourcentage.</strong>
 * L'ancien score notait {@code gatewayFeePercent} contre une constante de 5 %.
 * Or un plancher de frais s'applique, si bien que sur les petits montants le
 * pourcentage ne dit rien du coût réel : deux routes à 2,70 % et 4 % coûtent
 * exactement la même chose à l'expéditeur en dessous de ~2 500 FCFA.</li>
 *
 * <li><strong>L'absence de données ne vaut plus la note maximale.</strong> La
 * fiabilité sans historique valait 100 et la vitesse 80 : une passerelle jamais
 * utilisée battait donc une passerelle éprouvée, et comme les métriques vivaient
 * en mémoire, chaque redémarrage remettait tout le monde à « parfait ». Un prior
 * bayésien part bas et converge avec les preuves.</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RouteScorer {

    private final GatewayHealthMonitor health;
    private final FeeCalculator feeCalculator;
    private final FxRegistry fx;

    @Value("${routing.score.weight.cost:35}")
    private int weightCost;

    @Value("${routing.score.weight.margin:20}")
    private int weightMargin;

    @Value("${routing.score.weight.reliability:30}")
    private int weightReliability;

    @Value("${routing.score.weight.latency:15}")
    private int weightLatency;

    /** Pseudo-succès du prior. Faible : on ne crédite pas l'inconnu. */
    @Value("${routing.score.prior.alpha:1}")
    private double priorAlpha;

    /** Pseudo-échecs du prior. Élevé : l'inconnu démarre bas. */
    @Value("${routing.score.prior.beta:4}")
    private double priorBeta;

    /** Latence au-delà de laquelle le score de vitesse est nul. */
    @Value("${routing.score.latency-ceiling-ms:10000}")
    private long latencyCeilingMs;

    /** Note de vitesse tant qu'aucune latence n'a été observée. */
    @Value("${routing.score.cold-start-latency:20}")
    private int coldStartLatency;

    /**
     * Les poids doivent totaliser 100, sinon le score sort de l'échelle 0-100 et
     * tous les seuils exprimés dans cette échelle perdent leur sens. L'ancienne
     * implémentation ne vérifiait cela que dans un setter d'administration
     * inaccessible.
     */
    @PostConstruct
    void validateWeights() {
        int sum = weightCost + weightMargin + weightReliability + weightLatency;
        if (sum != 100) {
            throw new IllegalStateException(
                    "Les poids de scoring doivent totaliser 100, trouvé " + sum
                            + " (cost=" + weightCost + ", margin=" + weightMargin
                            + ", reliability=" + weightReliability + ", latency=" + weightLatency + ")");
        }
        log.info("Route scoring weights: cost={}, margin={}, reliability={}, latency={} (prior α={}, β={})",
                weightCost, weightMargin, weightReliability, weightLatency, priorAlpha, priorBeta);
    }

    /**
     * Note et classe les candidats. Le coût et la marge sont notés
     * <em>relativement au meilleur du lot</em> : on compare des options réelles
     * entre elles, pas à une constante arbitraire.
     */
    public List<ScoredRoute> rank(List<Candidate> candidates, RoutingContext request) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Priced> priced = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            priced.add(price(candidate, request));
        }

        long cheapest = priced.stream().mapToLong(p -> p.fees().getTotalFee()).min().orElse(1L);
        long bestMargin = priced.stream().mapToLong(p -> nz(p.fees().getNetMargin())).max().orElse(0L);

        List<ScoredRoute> scored = new ArrayList<>(priced.size());
        for (Priced p : priced) {
            var observations = health.observations(p.route().getGateway());

            int costScore = relativeCostScore(p.fees().getTotalFee(), cheapest);
            int marginScore = relativeMarginScore(nz(p.fees().getNetMargin()), bestMargin);
            int reliabilityScore = reliabilityScore(observations);
            int latencyScore = latencyScore(observations);

            int total = (costScore * weightCost
                    + marginScore * weightMargin
                    + reliabilityScore * weightReliability
                    + latencyScore * weightLatency) / 100;

            scored.add(ScoredRoute.builder()
                    .route(p.route())
                    .gateway(p.route().getGateway())
                    .totalScore(total)
                    .costScore(costScore)
                    .marginScore(marginScore)
                    .reliabilityScore(reliabilityScore)
                    .latencyScore(latencyScore)
                    .fees(p.fees())
                    .sourceAmount(request.amount())
                    .sourceCurrency(request.sourceCurrency())
                    .payoutAmount(p.payoutAmount())
                    .payoutCurrency(request.destCurrency())
                    .observations(observations.total())
                    .build());
        }

        scored.sort(null); // ordre naturel : meilleur d'abord, déterministe
        return List.copyOf(scored);
    }

    private Priced price(Candidate candidate, RoutingContext request) {
        GatewayRoute route = candidate.route();
        FeeBreakdown fees = feeCalculator.calculateFees(
                request.amount(), route.getGatewayFeePercent(), request.sourceCurrency());

        // La porte devise garantit que la conversion est déclarée ; on ne peut donc
        // pas arriver ici sans taux. La valeur de repli n'est là que par prudence.
        long payoutAmount = fx.convert(request.amount(), request.sourceCurrency(), request.destCurrency())
                .orElse(request.amount());

        return new Priced(route, fees, payoutAmount);
    }

    /**
     * 100 pour la route la moins chère, décroissant en proportion inverse du
     * surcoût. Une route deux fois plus chère obtient 50.
     */
    private int relativeCostScore(long cost, long cheapest) {
        if (cost <= 0 || cheapest <= 0) {
            return 100;
        }
        return clamp((int) Math.round(100.0 * cheapest / cost));
    }

    /**
     * 100 pour la meilleure marge du lot. Une marge négative — la plateforme paie
     * — tombe à zéro : le moteur ne doit jamais préférer une route déficitaire
     * sans que cela se voie dans le score.
     */
    private int relativeMarginScore(long margin, long bestMargin) {
        if (margin <= 0) {
            return 0;
        }
        if (bestMargin <= 0) {
            return 100;
        }
        return clamp((int) Math.round(100.0 * margin / bestMargin));
    }

    /**
     * Estimation bayésienne du taux de succès :
     * {@code (succès + α) / (total + α + β)}.
     *
     * <p>
     * Avec α=1 et β=4 : aucune observation → 20 %, 50 observations à 96 % → 89 %,
     * 500 observations à 96 % → 95 %.
     */
    private int reliabilityScore(GatewayHealthMonitor.Observations obs) {
        double estimate = (obs.successes() + priorAlpha) / (obs.total() + priorAlpha + priorBeta);
        return clamp((int) Math.round(estimate * 100));
    }

    private int latencyScore(GatewayHealthMonitor.Observations obs) {
        if (obs.total() == 0 || obs.avgLatencyMs() <= 0) {
            return coldStartLatency;
        }
        if (obs.avgLatencyMs() >= latencyCeilingMs) {
            return 0;
        }
        double ratio = 1.0 - ((double) obs.avgLatencyMs() / latencyCeilingMs);
        return clamp((int) Math.round(ratio * 100));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private record Priced(GatewayRoute route, FeeBreakdown fees, long payoutAmount) {
    }
}
