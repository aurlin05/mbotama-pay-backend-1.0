package com.mbotamapay.routing;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Routage par pays intermédiaire, quand aucune route directe n'existe.
 *
 * <p>
 * Trois corrections par rapport à la version précédente.
 *
 * <p>
 * <strong>Coût.</strong> La recherche interrogeait la base une à deux fois par
 * pays candidat, puis une fois par route pour le stock : cinquante à cent
 * requêtes pour un corridor sans pont, sur un endpoint de prévisualisation
 * public et un pool de cinq connexions. Le parcours se fait maintenant en
 * mémoire sur le graphe déjà chargé.
 *
 * <p>
 * <strong>Frais.</strong> Les pourcentages des tronçons étaient additionnés
 * comme s'ils s'appliquaient à la même base. Ils se composent :
 * {@code 1 - (1-f1)(1-f2)}. Sur deux tronçons à 3 %, l'addition donne 6 %, la
 * composition 5,91 % — l'écart croît avec le nombre de sauts.
 *
 * <p>
 * <strong>Exécutabilité.</strong> Un pont n'est déclaré exécutable que si des
 * comptes de transit sont réellement configurés pour chaque pays traversé. Sans
 * cela il reste calculé — utile au diagnostic et à la prévisualisation — mais le
 * moteur refuse de l'exécuter. L'implémentation actuelle du versement multi-
 * tronçons envoie le montant complet sur chaque tronçon et n'a aucune
 * compensation en cas d'échec intermédiaire : la fabriquer plus vite ne la rend
 * pas sûre.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BridgeRouter {

    private final CapabilityMatrix matrix;
    private final GatewayHealthMonitor health;
    private final RoutingPolicy policy;

    @Value("${routing.bridge.enabled:true}")
    private boolean enabled;

    @Value("${routing.bridge.max-hops:2}")
    private int maxHops;

    /** Surcoût de traitement par saut, en points de pourcentage. */
    @Value("${routing.bridge.fee-overhead-percent:0.50}")
    private BigDecimal feeOverheadPercent;

    /**
     * Comptes de transit configurés par pays hub, sous forme
     * {@code CI:+2250700000000,SN:+221770000000}. Tant qu'un pays traversé n'y
     * figure pas, aucun pont ne peut l'emprunter en exécution.
     */
    @Value("${routing.bridge.transit-accounts:}")
    private String transitAccountsRaw;

    private Map<Country, String> transitAccounts = Map.of();

    @jakarta.annotation.PostConstruct
    void parseTransitAccounts() {
        Map<Country, String> parsed = new EnumMap<>(Country.class);
        if (transitAccountsRaw != null && !transitAccountsRaw.isBlank()) {
            for (String entry : transitAccountsRaw.split(",")) {
                String[] parts = entry.split(":", 2);
                if (parts.length == 2) {
                    Country.fromIsoCode(parts[0].trim())
                            .ifPresent(c -> parsed.put(c, parts[1].trim()));
                }
            }
        }
        this.transitAccounts = Map.copyOf(parsed);
        log.info("Bridge routing: enabled={}, maxHops={}, transit accounts configured for {}",
                enabled, maxHops, parsed.keySet());
    }

    /**
     * Cherche le pont le moins coûteux entre deux pays.
     *
     * @return vide si le pont est désactivé, si une route directe existe, ou si
     *         aucun chemin n'est trouvé
     */
    public Optional<BridgeRoute> find(Country source, Country dest) {
        if (!enabled || source == dest) {
            return Optional.empty();
        }
        if (matrix.hasDirectRoute(source, dest)) {
            return Optional.empty();
        }

        List<BridgeRoute> found = new ArrayList<>();
        explore(source, dest, new ArrayList<>(), new ArrayList<>(), EnumSet.of(source), found);

        return found.stream().min(Comparator.comparing(BridgeRoute::totalFeePercent));
    }

    /** Tous les ponts trouvés, pour le diagnostic et l'administration. */
    public List<BridgeRoute> findAll(Country source, Country dest) {
        if (source == dest) {
            return List.of();
        }
        List<BridgeRoute> found = new ArrayList<>();
        explore(source, dest, new ArrayList<>(), new ArrayList<>(), EnumSet.of(source), found);
        found.sort(Comparator.comparing(BridgeRoute::totalFeePercent));
        return List.copyOf(found);
    }

    /**
     * Parcours en profondeur borné par {@code maxHops}. Le graphe compte une
     * dizaine de pays : l'exhaustivité est ici moins coûteuse qu'un Dijkstra, et
     * elle évite le biais de l'ancienne version, qui plaçait les « hubs » en tête
     * de liste puis retenait de toute façon le minimum global — la priorisation
     * des hubs n'avait donc aucun effet.
     */
    private void explore(Country current, Country dest, List<BridgeLeg> path,
            List<Country> hops, EnumSet<Country> visited, List<BridgeRoute> found) {

        if (hops.size() > maxHops) {
            return;
        }

        for (Country next : matrix.destinationsFrom(current)) {
            if (visited.contains(next)) {
                continue;
            }

            Optional<GatewayRoute> leg = bestUsableRoute(current, next);
            if (leg.isEmpty()) {
                continue;
            }

            List<BridgeLeg> extended = new ArrayList<>(path);
            extended.add(new BridgeLeg(current, next, leg.get().getGateway(),
                    leg.get().getGatewayFeePercent()));

            if (next == dest) {
                if (!hops.isEmpty()) { // au moins un intermédiaire, sinon c'est une route directe
                    found.add(build(extended, List.copyOf(hops)));
                }
                continue;
            }

            visited.add(next);
            hops.add(next);
            explore(next, dest, extended, hops, visited, found);
            hops.remove(hops.size() - 1);
            visited.remove(next);
        }
    }

    /**
     * Meilleure route utilisable pour un tronçon : implémentée, opérationnelle,
     * disjoncteur fermé, non suspendue, et la moins chère parmi celles-là.
     */
    private Optional<GatewayRoute> bestUsableRoute(Country from, Country to) {
        return matrix.routes(from, to).stream()
                .filter(r -> matrix.isImplemented(r.getGateway()))
                .filter(r -> matrix.isOperational(r.getGateway()))
                .filter(r -> health.isAvailable(r.getGateway()))
                .filter(r -> !policy.isSuspended(r.getGateway()))
                .min(Comparator.comparing(GatewayRoute::getGatewayFeePercent));
    }

    private BridgeRoute build(List<BridgeLeg> legs, List<Country> hops) {
        // Composition des frais : chaque tronçon prélève sur ce qui lui parvient.
        BigDecimal remaining = BigDecimal.ONE;
        for (BridgeLeg leg : legs) {
            BigDecimal legFee = leg.feePercent()
                    .add(feeOverheadPercent)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_EVEN);
            remaining = remaining.multiply(BigDecimal.ONE.subtract(legFee));
        }
        BigDecimal totalFeePercent = BigDecimal.ONE.subtract(remaining)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.CEILING);

        List<String> blockers = new ArrayList<>();
        for (Country hop : hops) {
            if (!transitAccounts.containsKey(hop)) {
                blockers.add("aucun compte de transit configuré pour " + hop.getIsoCode());
            }
        }

        return BridgeRoute.builder()
                .legs(List.copyOf(legs))
                .hops(hops)
                .totalFeePercent(totalFeePercent)
                .executable(blockers.isEmpty())
                .blockers(List.copyOf(blockers))
                .build();
    }

    public Optional<String> transitAccount(Country country) {
        return Optional.ofNullable(transitAccounts.get(country));
    }

    // === Types ===

    @Builder
    public record BridgeRoute(
            List<BridgeLeg> legs,
            List<Country> hops,
            BigDecimal totalFeePercent,
            boolean executable,
            List<String> blockers) {

        public int hopCount() {
            return hops.size();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(legs.get(0).from().getIsoCode());
            for (BridgeLeg leg : legs) {
                sb.append(" → ").append(leg.to().getIsoCode());
            }
            return sb.toString();
        }
    }

    public record BridgeLeg(Country from, Country to, GatewayType gateway, BigDecimal feePercent) {
    }
}
