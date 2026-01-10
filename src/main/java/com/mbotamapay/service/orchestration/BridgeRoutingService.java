package com.mbotamapay.service.orchestration;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.repository.GatewayRouteRepository;
import com.mbotamapay.repository.GatewayStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Service de routage par ponts (Bridge Routing)
 * 
 * Quand aucune route directe n'existe entre deux pays,
 * ce service trouve un chemin via un ou plusieurs pays intermédiaires.
 * 
 * Exemple: Guinée (GN) → Nigeria (NG)
 * - Pas de route directe
 * - Pont trouvé: GN → CI → NG (si les deux segments existent)
 * 
 * Algorithme:
 * 1. Identifier les "hubs" (pays avec beaucoup de connexions)
 * 2. Chercher un chemin à 1 saut (source → hub → dest)
 * 3. Si pas trouvé, chercher à 2 sauts max
 * 4. Calculer les frais cumulés
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BridgeRoutingService {

    private final GatewayRouteRepository routeRepository;
    private final GatewayStockRepository stockRepository;
    private final GatewayHealthMonitor healthMonitor;

    // Pays "hubs" avec forte connectivité (prioritaires pour les ponts)
    private static final List<Country> HUB_COUNTRIES = List.of(
            Country.COTE_DIVOIRE,  // Hub principal Afrique de l'Ouest
            Country.SENEGAL,       // Hub secondaire
            Country.BENIN,         // Connecté à beaucoup de pays
            Country.CAMEROON       // Hub Afrique Centrale
    );

    // Frais additionnels pour le bridge (en %)
    private static final BigDecimal BRIDGE_FEE_OVERHEAD = new BigDecimal("0.50");

    // Nombre maximum de sauts
    private static final int MAX_HOPS = 2;

    /**
     * Trouve une route via un pont si aucune route directe n'existe
     */
    public Optional<BridgeRoute> findBridgeRoute(Country source, Country dest, Long amount) {
        log.info("Searching bridge route: {} -> {}, amount={}", source, dest, amount);

        // 1. Vérifier d'abord s'il existe une route directe
        List<GatewayRoute> directRoutes = routeRepository.findActiveRoutes(source, dest);
        if (!directRoutes.isEmpty()) {
            log.debug("Direct route exists, no bridge needed");
            return Optional.empty();
        }

        // 2. Chercher un pont à 1 saut via les hubs
        Optional<BridgeRoute> oneHopBridge = findOneHopBridge(source, dest, amount);
        if (oneHopBridge.isPresent()) {
            log.info("Found 1-hop bridge: {} -> {} -> {}", 
                    source, oneHopBridge.get().getBridgeCountries().get(0), dest);
            return oneHopBridge;
        }

        // 3. Chercher un pont à 2 sauts si nécessaire
        Optional<BridgeRoute> twoHopBridge = findTwoHopBridge(source, dest, amount);
        if (twoHopBridge.isPresent()) {
            log.info("Found 2-hop bridge: {} -> {} -> {} -> {}", 
                    source, 
                    twoHopBridge.get().getBridgeCountries().get(0),
                    twoHopBridge.get().getBridgeCountries().get(1),
                    dest);
            return twoHopBridge;
        }

        log.warn("No bridge route found for {} -> {}", source, dest);
        return Optional.empty();
    }

    /**
     * Trouve un pont à 1 saut (source → bridge → dest)
     */
    private Optional<BridgeRoute> findOneHopBridge(Country source, Country dest, Long amount) {
        List<BridgeRoute> candidates = new ArrayList<>();

        // Prioriser les hubs
        List<Country> potentialBridges = new ArrayList<>(HUB_COUNTRIES);
        
        // Ajouter tous les autres pays comme ponts potentiels
        for (Country country : Country.values()) {
            if (!potentialBridges.contains(country) && country != source && country != dest) {
                potentialBridges.add(country);
            }
        }

        for (Country bridge : potentialBridges) {
            if (bridge == source || bridge == dest) continue;

            // Vérifier si les deux segments existent
            List<GatewayRoute> leg1Routes = routeRepository.findActiveRoutes(source, bridge);
            List<GatewayRoute> leg2Routes = routeRepository.findActiveRoutes(bridge, dest);

            if (!leg1Routes.isEmpty() && !leg2Routes.isEmpty()) {
                // Trouver la meilleure combinaison
                BridgeRoute bridgeRoute = buildBridgeRoute(source, dest, List.of(bridge), 
                        leg1Routes, leg2Routes, null, amount);
                
                if (bridgeRoute != null && bridgeRoute.isViable()) {
                    candidates.add(bridgeRoute);
                }
            }
        }

        // Retourner le pont avec les frais les plus bas
        return candidates.stream()
                .min(Comparator.comparing(BridgeRoute::getTotalFeePercent));
    }

    /**
     * Trouve un pont à 2 sauts (source → bridge1 → bridge2 → dest)
     */
    private Optional<BridgeRoute> findTwoHopBridge(Country source, Country dest, Long amount) {
        List<BridgeRoute> candidates = new ArrayList<>();

        // Utiliser uniquement les hubs pour les ponts à 2 sauts (limiter la complexité)
        for (Country bridge1 : HUB_COUNTRIES) {
            if (bridge1 == source || bridge1 == dest) continue;

            for (Country bridge2 : HUB_COUNTRIES) {
                if (bridge2 == source || bridge2 == dest || bridge2 == bridge1) continue;

                // Vérifier les 3 segments
                List<GatewayRoute> leg1Routes = routeRepository.findActiveRoutes(source, bridge1);
                List<GatewayRoute> leg2Routes = routeRepository.findActiveRoutes(bridge1, bridge2);
                List<GatewayRoute> leg3Routes = routeRepository.findActiveRoutes(bridge2, dest);

                if (!leg1Routes.isEmpty() && !leg2Routes.isEmpty() && !leg3Routes.isEmpty()) {
                    BridgeRoute bridgeRoute = buildBridgeRoute(source, dest, 
                            List.of(bridge1, bridge2), leg1Routes, leg2Routes, leg3Routes, amount);
                    
                    if (bridgeRoute != null && bridgeRoute.isViable()) {
                        candidates.add(bridgeRoute);
                    }
                }
            }
        }

        return candidates.stream()
                .min(Comparator.comparing(BridgeRoute::getTotalFeePercent));
    }

    /**
     * Construit un objet BridgeRoute avec les meilleures routes pour chaque segment
     */
    private BridgeRoute buildBridgeRoute(Country source, Country dest, List<Country> bridges,
            List<GatewayRoute> leg1Routes, List<GatewayRoute> leg2Routes, 
            List<GatewayRoute> leg3Routes, Long amount) {
        
        // Sélectionner la meilleure route pour chaque segment (frais les plus bas + gateway disponible)
        GatewayRoute bestLeg1 = selectBestRoute(leg1Routes, amount);
        GatewayRoute bestLeg2 = selectBestRoute(leg2Routes, amount);
        GatewayRoute bestLeg3 = leg3Routes != null ? selectBestRoute(leg3Routes, amount) : null;

        if (bestLeg1 == null || bestLeg2 == null || (leg3Routes != null && bestLeg3 == null)) {
            return null;
        }

        // Calculer les frais totaux
        BigDecimal totalFees = bestLeg1.getGatewayFeePercent()
                .add(bestLeg2.getGatewayFeePercent())
                .add(BRIDGE_FEE_OVERHEAD); // Overhead pour le bridge

        if (bestLeg3 != null) {
            totalFees = totalFees.add(bestLeg3.getGatewayFeePercent())
                    .add(BRIDGE_FEE_OVERHEAD); // Overhead supplémentaire
        }

        List<BridgeLeg> legs = new ArrayList<>();
        legs.add(new BridgeLeg(source, bridges.get(0), bestLeg1.getGateway(), bestLeg1.getGatewayFeePercent()));
        
        if (bridges.size() == 1) {
            legs.add(new BridgeLeg(bridges.get(0), dest, bestLeg2.getGateway(), bestLeg2.getGatewayFeePercent()));
        } else {
            legs.add(new BridgeLeg(bridges.get(0), bridges.get(1), bestLeg2.getGateway(), bestLeg2.getGatewayFeePercent()));
            legs.add(new BridgeLeg(bridges.get(1), dest, bestLeg3.getGateway(), bestLeg3.getGatewayFeePercent()));
        }

        return BridgeRoute.builder()
                .sourceCountry(source)
                .destCountry(dest)
                .bridgeCountries(bridges)
                .legs(legs)
                .totalFeePercent(totalFees)
                .hopCount(bridges.size())
                .viable(true)
                .build();
    }

    /**
     * Sélectionne la meilleure route parmi les options (frais + disponibilité gateway)
     */
    private GatewayRoute selectBestRoute(List<GatewayRoute> routes, Long amount) {
        return routes.stream()
                .filter(route -> healthMonitor.isAvailable(route.getGateway()))
                .filter(route -> hasStock(route.getGateway(), route.getDestCountry(), amount))
                .min(Comparator.comparing(GatewayRoute::getGatewayFeePercent))
                .orElse(null);
    }

    /**
     * Vérifie si le stock est suffisant
     */
    private boolean hasStock(GatewayType gateway, Country country, Long amount) {
        Optional<GatewayStock> stock = stockRepository.findByGatewayAndCountry(gateway, country);
        return stock.map(s -> s.hasSufficientBalance(amount)).orElse(true); // Si pas de stock configuré, on suppose OK
    }

    /**
     * Retourne tous les ponts possibles pour un corridor (pour affichage/debug)
     */
    public List<BridgeRoute> findAllBridgeRoutes(Country source, Country dest, Long amount) {
        List<BridgeRoute> allRoutes = new ArrayList<>();

        // 1 hop bridges
        for (Country bridge : Country.values()) {
            if (bridge == source || bridge == dest) continue;

            List<GatewayRoute> leg1 = routeRepository.findActiveRoutes(source, bridge);
            List<GatewayRoute> leg2 = routeRepository.findActiveRoutes(bridge, dest);

            if (!leg1.isEmpty() && !leg2.isEmpty()) {
                BridgeRoute route = buildBridgeRoute(source, dest, List.of(bridge), leg1, leg2, null, amount);
                if (route != null && route.isViable()) {
                    allRoutes.add(route);
                }
            }
        }

        // Trier par frais
        allRoutes.sort(Comparator.comparing(BridgeRoute::getTotalFeePercent));
        return allRoutes;
    }

    // === Inner Classes ===

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BridgeRoute {
        private Country sourceCountry;
        private Country destCountry;
        private List<Country> bridgeCountries;
        private List<BridgeLeg> legs;
        private BigDecimal totalFeePercent;
        private int hopCount;
        private boolean viable;

        public String getRouteDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(sourceCountry.getIsoCode());
            for (Country bridge : bridgeCountries) {
                sb.append(" → ").append(bridge.getIsoCode());
            }
            sb.append(" → ").append(destCountry.getIsoCode());
            return sb.toString();
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class BridgeLeg {
        private Country from;
        private Country to;
        private GatewayType gateway;
        private BigDecimal feePercent;
    }
}
