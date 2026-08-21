package com.mbotamapay.routing;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.MobileOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Le moteur de routage : éligibilité, puis arbitrage, puis politique.
 *
 * <p>
 * Point d'entrée unique. La prévisualisation et l'exécution passent exactement
 * par ici, donc le prix coté est bien celui de la route qui sera empruntée — à
 * ceci près que l'état du système peut changer entre les deux, ce que le devis
 * épinglé ({@link RouteQuoteService}) résout.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoutingEngine {

    private final EligibilityFilter eligibility;
    private final RouteScorer scorer;
    private final RoutingPolicy policy;
    private final BridgeRouter bridgeRouter;

    /**
     * Construit un contexte de routage à partir de deux numéros de téléphone.
     *
     * @throws NoRouteAvailableException si un pays ne peut pas être déterminé
     */
    public RoutingContext contextFor(String senderPhone, String recipientPhone, long amount) {
        Country source = Country.fromPhoneNumber(senderPhone)
                .orElseThrow(() -> new NoRouteAvailableException("?", List.of(),
                        "Pays de l'expéditeur non reconnu à partir du numéro fourni"));
        Country dest = Country.fromPhoneNumber(recipientPhone)
                .orElseThrow(() -> new NoRouteAvailableException("?", List.of(),
                        "Pays du bénéficiaire non reconnu à partir du numéro fourni"));

        return RoutingContext.builder()
                .sourceCountry(source)
                .destCountry(dest)
                .sourceOperator(MobileOperator.fromPhoneNumber(senderPhone, source).orElse(null))
                .destOperator(MobileOperator.fromPhoneNumber(recipientPhone, dest).orElse(null))
                .amount(amount)
                .senderPhone(senderPhone)
                .recipientPhone(recipientPhone)
                .build();
    }

    /**
     * Décide, sans jamais lever d'exception : une décision {@code NO_ROUTE} est
     * une réponse valide, porteuse des motifs. C'est à l'appelant de choisir
     * entre l'afficher (prévisualisation) et refuser (exécution).
     */
    public RoutingDecision decide(RoutingContext request) {
        long started = System.currentTimeMillis();

        // --- Phase 1 : éligibilité ---
        Eligibility.Result eligible = eligibility.apply(request);

        if (!eligible.isEmpty()) {
            // --- Phase 2 : arbitrage ---
            List<ScoredRoute> ranked = scorer.rank(eligible.eligible(), request);

            // --- Phase 3 : politique ---
            RoutingPolicy.Outcome applied = policy.apply(ranked, request, LocalDateTime.now());

            return RoutingDecision.builder()
                    .corridor(request.corridor())
                    .amount(request.amount())
                    .sourceCurrency(request.sourceCurrency())
                    .destCurrency(request.destCurrency())
                    .outcome(RoutingDecision.Outcome.DIRECT)
                    .ranked(applied.ranked())
                    .rejected(eligible.rejected())
                    .policyOverrides(applied.overrides())
                    .decisionTimeMs(System.currentTimeMillis() - started)
                    .build();
        }

        // --- Pas de route directe : chercher un pont ---
        Optional<BridgeRouter.BridgeRoute> bridge = bridgeRouter.find(
                request.sourceCountry(), request.destCountry());

        if (bridge.isPresent()) {
            BridgeRouter.BridgeRoute route = bridge.get();
            return RoutingDecision.builder()
                    .corridor(request.corridor())
                    .amount(request.amount())
                    .sourceCurrency(request.sourceCurrency())
                    .destCurrency(request.destCurrency())
                    .outcome(route.executable()
                            ? RoutingDecision.Outcome.BRIDGE
                            : RoutingDecision.Outcome.BRIDGE_NOT_EXECUTABLE)
                    .ranked(List.of())
                    .rejected(eligible.rejected())
                    .policyOverrides(List.of())
                    .bridge(route)
                    .decisionTimeMs(System.currentTimeMillis() - started)
                    .build();
        }

        return RoutingDecision.builder()
                .corridor(request.corridor())
                .amount(request.amount())
                .sourceCurrency(request.sourceCurrency())
                .destCurrency(request.destCurrency())
                .outcome(RoutingDecision.Outcome.NO_ROUTE)
                .ranked(List.of())
                .rejected(eligible.rejected())
                .policyOverrides(List.of())
                .decisionTimeMs(System.currentTimeMillis() - started)
                .build();
    }

    /**
     * Décide et exige une route exécutable.
     *
     * @throws NoRouteAvailableException avec les motifs détaillés
     */
    public RoutingDecision decideOrThrow(RoutingContext request) {
        RoutingDecision decision = decide(request);
        if (!decision.isExecutable()) {
            log.warn("No executable route for {}: {}", request.corridor(), decision.explain());
            throw new NoRouteAvailableException(
                    request.corridor(), decision.rejected(), decision.explain());
        }
        return decision;
    }
}
