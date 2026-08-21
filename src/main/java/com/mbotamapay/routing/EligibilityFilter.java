package com.mbotamapay.routing;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.repository.GatewayStockRepository;
import com.mbotamapay.routing.Eligibility.Candidate;
import com.mbotamapay.routing.Eligibility.Rejection;
import com.mbotamapay.routing.Eligibility.Verdict;
import com.mbotamapay.routing.gates.EligibilityGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 du moteur : applique les portes, dans l'ordre, à chaque candidat.
 *
 * <p>
 * Une route sort à la première porte qui la refuse — inutile d'évaluer la
 * liquidité d'une passerelle qui ne dessert pas le pays. Les portes sont
 * injectées triées par {@code @Order}, du moins coûteux au plus coûteux.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EligibilityFilter {

    private final List<EligibilityGate> gates;
    private final CapabilityMatrix matrix;
    private final GatewayStockRepository stockRepository;

    public Eligibility.Result apply(RoutingContext request) {
        List<GatewayRoute> routes = matrix.routes(request.sourceCountry(), request.destCountry());

        if (routes.isEmpty()) {
            return new Eligibility.Result(List.of(), List.of());
        }

        // Une seule requête pour toute la phase, quel que soit le nombre de
        // candidats. La liquidité était auparavant lue une fois par route.
        EligibilityGate.GateContext context = new EligibilityGate.GateContext(
                request, loadStocks(request));

        List<Candidate> eligible = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();

        for (GatewayRoute route : routes) {
            Candidate candidate = new Candidate(route);
            Rejection rejection = firstRefusal(candidate, context);
            if (rejection == null) {
                eligible.add(candidate);
            } else {
                rejected.add(rejection);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Eligibility {}: {} eligible, {} rejected",
                    request.corridor(), eligible.size(), rejected.size());
        }

        return new Eligibility.Result(List.copyOf(eligible), List.copyOf(rejected));
    }

    private Rejection firstRefusal(Candidate candidate, EligibilityGate.GateContext context) {
        for (EligibilityGate gate : gates) {
            Verdict verdict = gate.test(candidate, context);
            if (!verdict.eligible()) {
                return new Rejection(candidate.gateway(), gate.name(), verdict.reason());
            }
        }
        return null;
    }

    private Map<GatewayType, GatewayStock> loadStocks(RoutingContext request) {
        Map<GatewayType, GatewayStock> stocks = new EnumMap<>(GatewayType.class);
        stockRepository.findByCountry(request.destCountry())
                .forEach(stock -> stocks.put(stock.getGateway(), stock));
        return stocks;
    }
}
