package com.mbotamapay.routing;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.GatewayCapabilities;
import com.mbotamapay.repository.GatewayRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Réconcilie, au démarrage, les trois déclarations de capacités qui coexistent
 * dans le système et qui pouvaient jusqu'ici se contredire en silence :
 *
 * <ol>
 * <li>la table {@code gateway_routes}, tenue à la main dans les migrations ;</li>
 * <li>{@link GatewayCapabilities}, déclarée par chaque passerelle ;</li>
 * <li>{@link MobileOperator#getSupportedGateways()}, déclarée par opérateur.</li>
 * </ol>
 *
 * <p>
 * Rien ne les rapprochait : une ligne de route désignant une passerelle qui ne
 * dessert pas le pays de destination passait inaperçue jusqu'au versement, où
 * elle produisait un appel vers un point d'accès inexistant. C'est exactement le
 * scénario qu'une des migrations a créé puis corrigé à la main (une route SN↔CG
 * attribuée à un agrégateur qui ne couvre pas le Congo).
 *
 * <p>
 * Les incohérences sont journalisées au démarrage. Avec
 * {@code routing.validation.fail-on-inconsistency=true}, elles empêchent le
 * démarrage — recommandé en production.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CapabilityConsistencyValidator {

    private final GatewayRouteRepository routeRepository;
    private final CapabilityMatrix matrix;
    private final FxRegistry fx;

    @Value("${routing.validation.fail-on-inconsistency:false}")
    private boolean failOnInconsistency;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        Report report = run();

        report.warnings().forEach(w -> log.warn("Routing capability warning: {}", w));
        report.errors().forEach(e -> log.error("Routing capability error: {}", e));

        if (report.isClean()) {
            log.info("Routing capability check passed: {} routes consistent across declarations",
                    matrix.routeCount());
            return;
        }

        log.warn("Routing capability check: {} error(s), {} warning(s)",
                report.errors().size(), report.warnings().size());

        if (failOnInconsistency && !report.errors().isEmpty()) {
            throw new IllegalStateException(
                    "Incohérences de routage bloquantes au démarrage :\n  - "
                            + String.join("\n  - ", report.errors()));
        }
    }

    /** Exécute les contrôles sans lever. Exposé pour l'administration et les tests. */
    public Report run() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<GatewayRoute> routes = routeRepository.findAll().stream()
                .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                .toList();

        Set<Country> reachableDestinations = EnumSet.noneOf(Country.class);

        for (GatewayRoute route : routes) {
            GatewayType gateway = route.getGateway();
            Country source = route.getSourceCountry();
            Country dest = route.getDestCountry();
            String label = gateway.getDisplayName() + " " + source.getIsoCode() + "→" + dest.getIsoCode();

            // 1. Implémentation présente
            Optional<GatewayCapabilities> capsOpt = matrix.capabilities(gateway);
            if (capsOpt.isEmpty()) {
                errors.add(label + " : aucune implémentation Java pour cette passerelle");
                continue;
            }
            GatewayCapabilities caps = capsOpt.get();

            // 2. La passerelle verse-t-elle vraiment là-bas ?
            if (!caps.canPayoutTo(dest)) {
                errors.add(label + " : la passerelle ne déclare pas verser vers "
                        + dest.getDisplayName());
            }

            // 3. Sait-elle encaisser depuis le pays source ?
            if (!caps.canCollectFrom(source)) {
                errors.add(label + " : la passerelle ne déclare pas encaisser depuis "
                        + source.getDisplayName());
            }

            // 4. Manipule-t-elle les devises du corridor ?
            if (!caps.handlesCurrency(dest.getCurrency())) {
                errors.add(label + " : la passerelle ne déclare pas la devise " + dest.getCurrency());
            }

            // 5. Le corridor traverse-t-il une frontière monétaire sans taux ?
            if (!source.getCurrency().equals(dest.getCurrency())
                    && !fx.isConvertible(source.getCurrency(), dest.getCurrency())) {
                warnings.add(label + " : corridor " + source.getCurrency() + "→" + dest.getCurrency()
                        + " sans taux déclaré, il sera refusé à l'exécution");
            }

            // 6. Au moins un opérateur du pays de destination est-il joignable ?
            Set<MobileOperator> destOperators = MobileOperator.getOperatorsForCountry(dest);
            boolean anyReachable = destOperators.stream().anyMatch(caps::canReach);
            if (!destOperators.isEmpty() && !anyReachable) {
                errors.add(label + " : aucun opérateur de " + dest.getDisplayName()
                        + " n'est déclaré joignable par cette passerelle");
            } else if (anyReachable) {
                reachableDestinations.add(dest);
            }

            // 7. Divergence entre la déclaration opérateur et la déclaration passerelle
            for (MobileOperator operator : destOperators) {
                boolean operatorSaysYes = operator.supportsGateway(gateway);
                boolean gatewaySaysYes = caps.canReach(operator);
                if (operatorSaysYes != gatewaySaysYes) {
                    warnings.add(String.format(
                            "%s / %s : déclarations contradictoires (opérateur=%s, passerelle=%s)",
                            gateway.getDisplayName(), operator.name(),
                            operatorSaysYes, gatewaySaysYes));
                }
            }

            // 8. Passerelle intégrée mais non opérationnelle
            if (!matrix.isOperational(gateway)) {
                warnings.add(label + " : passerelle non opérationnelle (identifiants absents "
                        + "ou intégration non validée), route inerte");
            }
        }

        // 9. Pays desservis en payout mais qu'aucune route n'atteint
        for (Country country : Country.values()) {
            boolean anyRouteTo = routes.stream().anyMatch(r -> r.getDestCountry() == country);
            if (!anyRouteTo) {
                warnings.add("Aucune route n'atteint " + country.getDisplayName()
                        + " : le pays est injoignable, y compris par pont");
            }
        }

        // 10. Collisions de préfixes opérateurs
        warnings.addAll(prefixCollisions());

        return new Report(List.copyOf(errors), List.copyOf(warnings));
    }

    /**
     * Deux opérateurs d'un même pays revendiquant le même préfixe.
     *
     * <p>
     * La détection d'opérateur retient le préfixe le plus long ; à longueur
     * égale, c'est l'ordre de déclaration de l'énumération qui tranche —
     * autrement dit, le hasard. Or l'opérateur est maintenant une porte
     * d'éligibilité : une collision envoie silencieusement les numéros concernés
     * vers le mauvais réseau, ou les fait rejeter.
     */
    private List<String> prefixCollisions() {
        List<String> found = new ArrayList<>();
        for (Country country : Country.values()) {
            Map<String, List<MobileOperator>> byPrefix = new LinkedHashMap<>();
            for (MobileOperator operator : MobileOperator.getOperatorsForCountry(country)) {
                for (String prefix : operator.getPrefixes()) {
                    byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(operator);
                }
            }
            byPrefix.forEach((prefix, operators) -> {
                if (operators.size() > 1) {
                    found.add(String.format("%s : le préfixe %s est revendiqué par %s",
                            country.getIsoCode(), prefix,
                            operators.stream().map(Enum::name).collect(Collectors.joining(" et "))));
                }
            });
        }
        return found;
    }

    public record Report(List<String> errors, List<String> warnings) {
        public boolean isClean() {
            return errors.isEmpty() && warnings.isEmpty();
        }
    }
}
