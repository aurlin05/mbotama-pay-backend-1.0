package com.mbotamapay.routing;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.gateway.GatewayCapabilities;
import com.mbotamapay.gateway.PayoutGateway;
import com.mbotamapay.repository.GatewayRouteRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Graphe de routage et capacités partenaires, tenus en mémoire.
 *
 * <p>
 * La table {@code gateway_routes} est de la donnée de configuration : elle
 * change quand on signe un partenaire, pas à chaque requête. La recherche de
 * pont interrogeait pourtant la base une à deux fois par pays candidat, soit
 * cinquante à cent requêtes pour un corridor sans route — sur un endpoint de
 * prévisualisation public et un pool de cinq connexions.
 *
 * <p>
 * Tout est désormais chargé en un balayage, rafraîchi périodiquement, et
 * exposé sous forme de graphe. La recherche de pont devient un parcours en
 * mémoire, sans aucune requête.
 */
@Component
@Slf4j
public class CapabilityMatrix {

    private final GatewayRouteRepository routeRepository;
    private final Map<GatewayType, PayoutGateway> gatewaysByType;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public CapabilityMatrix(GatewayRouteRepository routeRepository, List<PayoutGateway> payoutGateways) {
        this.routeRepository = routeRepository;
        this.gatewaysByType = payoutGateways.stream()
                .collect(Collectors.toMap(PayoutGateway::getGatewayType, g -> g, (a, b) -> a,
                        () -> new EnumMap<>(GatewayType.class)));
    }

    @PostConstruct
    public void load() {
        refresh();
    }

    /**
     * Recharge le graphe depuis la base. Appelé au démarrage, périodiquement, et
     * par l'administration après modification des routes.
     */
    @Scheduled(fixedDelayString = "${routing.matrix.refresh-ms:300000}")
    public void refresh() {
        try {
            List<GatewayRoute> all = routeRepository.findAll().stream()
                    .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                    .toList();

            Map<String, List<GatewayRoute>> byCorridor = new HashMap<>();
            Map<Country, Set<Country>> adjacency = new EnumMap<>(Country.class);

            for (GatewayRoute route : all) {
                byCorridor.computeIfAbsent(key(route.getSourceCountry(), route.getDestCountry()),
                        k -> new ArrayList<>()).add(route);
                if (route.getSourceCountry() != route.getDestCountry()) {
                    adjacency.computeIfAbsent(route.getSourceCountry(), k -> EnumSet.noneOf(Country.class))
                            .add(route.getDestCountry());
                }
            }

            byCorridor.values().forEach(list -> list.sort(
                    Comparator.comparing(GatewayRoute::getPriority,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(GatewayRoute::getGatewayFeePercent,
                                    Comparator.nullsLast(Comparator.naturalOrder()))));

            Snapshot fresh = new Snapshot(Map.copyOf(byCorridor), Map.copyOf(adjacency), all.size());
            snapshot.set(fresh);

            log.info("Routing matrix loaded: {} routes, {} corridors, {} countries with outbound links",
                    fresh.routeCount(), fresh.byCorridor().size(), fresh.adjacency().size());
        } catch (Exception e) {
            // Un échec de rafraîchissement ne doit pas vider le graphe en service.
            log.error("Routing matrix refresh failed, keeping previous snapshot: {}", e.getMessage());
        }
    }

    /** Routes déclarées pour un corridor, triées par priorité puis par frais. */
    public List<GatewayRoute> routes(Country source, Country dest) {
        return snapshot.get().byCorridor().getOrDefault(key(source, dest), List.of());
    }

    public boolean hasDirectRoute(Country source, Country dest) {
        return !routes(source, dest).isEmpty();
    }

    /** Destinations atteignables en un saut depuis ce pays (hors local). */
    public Set<Country> destinationsFrom(Country source) {
        return snapshot.get().adjacency().getOrDefault(source, Set.of());
    }

    public Optional<PayoutGateway> gateway(GatewayType type) {
        return Optional.ofNullable(gatewaysByType.get(type));
    }

    public Optional<GatewayCapabilities> capabilities(GatewayType type) {
        return gateway(type).map(PayoutGateway::capabilities);
    }

    /** Une passerelle déclarée en base mais sans implémentation Spring. */
    public boolean isImplemented(GatewayType type) {
        return gatewaysByType.containsKey(type);
    }

    public boolean isOperational(GatewayType type) {
        return gateway(type).map(PayoutGateway::isOperational).orElse(false);
    }

    public Collection<PayoutGateway> allGateways() {
        return gatewaysByType.values();
    }

    public int routeCount() {
        return snapshot.get().routeCount();
    }

    public Set<String> corridors() {
        return snapshot.get().byCorridor().keySet();
    }

    private static String key(Country source, Country dest) {
        return source.name() + ">" + dest.name();
    }

    private record Snapshot(
            Map<String, List<GatewayRoute>> byCorridor,
            Map<Country, Set<Country>> adjacency,
            int routeCount) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), 0);
        }
    }
}
