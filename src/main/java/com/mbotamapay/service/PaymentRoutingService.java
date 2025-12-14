package com.mbotamapay.service;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.dto.routing.RoutingDecision;
import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.repository.GatewayRouteRepository;
import com.mbotamapay.repository.GatewayStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service de routage intelligent des paiements
 * 
 * Algorithme:
 * 1. Détecter les pays source et destination
 * 2. Trouver les routes disponibles
 * 3. Sélectionner la meilleure route (priorité, frais)
 * 4. Si nécessaire, utiliser le stock pour cross-gateway
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentRoutingService {

    private final GatewayRouteRepository routeRepository;
    private final GatewayStockRepository stockRepository;
    private final FeeCalculator feeCalculator;

    @Value("${routing.prefer-direct-route:true}")
    private boolean preferDirectRoute;

    /**
     * Détecte le pays à partir d'un numéro de téléphone
     */
    public Optional<Country> detectCountry(String phoneNumber) {
        return Country.fromPhoneNumber(phoneNumber);
    }

    /**
     * Détecte l'opérateur mobile
     */
    public Optional<MobileOperator> detectOperator(String phoneNumber, Country country) {
        return MobileOperator.fromPhoneNumber(phoneNumber, country);
    }

    /**
     * Détermine la meilleure route pour une transaction
     */
    public RoutingDecision determineRoute(String senderPhone, String recipientPhone, Long amount) {
        log.info("Determining route: sender={}, recipient={}, amount={}",
                senderPhone, recipientPhone, amount);

        // 1. Détecter les pays
        Optional<Country> sourceOpt = detectCountry(senderPhone);
        Optional<Country> destOpt = detectCountry(recipientPhone);

        if (sourceOpt.isEmpty() || destOpt.isEmpty()) {
            log.warn("Could not detect countries for routing");
            return RoutingDecision.builder()
                    .routeFound(false)
                    .routingReason("Pays non détecté: source=" + senderPhone + ", dest=" + recipientPhone)
                    .build();
        }

        Country source = sourceOpt.get();
        Country dest = destOpt.get();

        log.info("Detected countries: {} -> {}", source, dest);

        // 2. Chercher les routes disponibles
        List<GatewayRoute> routes = routeRepository.findActiveRoutes(source, dest);

        if (routes.isEmpty()) {
            log.warn("No routes found for {} -> {}", source, dest);
            return RoutingDecision.builder()
                    .sourceCountry(source)
                    .destCountry(dest)
                    .routeFound(false)
                    .routingReason("Aucune route configurée pour " + source + " -> " + dest)
                    .build();
        }

        // 3. Sélectionner la meilleure route
        GatewayRoute bestRoute = selectBestRoute(routes, dest, amount);

        // 4. Calculer les frais
        FeeBreakdown fees = feeCalculator.calculateFees(amount, bestRoute.getGatewayFeePercent());

        // 5. Déterminer si le stock est nécessaire
        boolean useStock = !bestRoute.isLocalTransfer() && preferDirectRoute;

        // Si cross-gateway, vérifier le stock
        if (useStock) {
            Optional<GatewayStock> stock = stockRepository
                    .findByGatewayAndCountry(bestRoute.getGateway(), dest);

            useStock = stock.isPresent() && stock.get().hasSufficientBalance(amount);
            if (!useStock && stock.isPresent()) {
                log.warn("Insufficient stock for gateway {} in country {}: {} < {}",
                        bestRoute.getGateway(), dest, stock.get().getBalance(), amount);
            }
        }

        String reason = buildRoutingReason(bestRoute, useStock);

        RoutingDecision decision = RoutingDecision.builder()
                .sourceCountry(source)
                .destCountry(dest)
                .collectionGateway(bestRoute.getGateway())
                .payoutGateway(bestRoute.getGateway())
                .useStock(useStock)
                .fees(fees)
                .routingReason(reason)
                .routeFound(true)
                .build();

        log.info("Routing decision: gateway={}, fees={}%, useStock={}",
                bestRoute.getGateway(), fees.getDisplayPercent(), useStock);

        return decision;
    }

    /**
     * Sélectionne la meilleure route parmi les options disponibles
     */
    private GatewayRoute selectBestRoute(List<GatewayRoute> routes, Country dest, Long amount) {
        // Filtrer d'abord les routes avec stock suffisant
        List<GatewayRoute> withStock = new java.util.ArrayList<>();
        for (GatewayRoute route : routes) {
            Optional<GatewayStock> stock = stockRepository.findByGatewayAndCountry(route.getGateway(), dest);
            if (stock.isPresent() && stock.get().hasSufficientBalance(amount)) {
                withStock.add(route);
            }
        }
        List<GatewayRoute> candidates = withStock.isEmpty() ? routes : withStock;
        // Choisir la route avec le plus faible pourcentage de frais
        candidates.sort(java.util.Comparator
                .comparing(GatewayRoute::getGatewayFeePercent)
                .thenComparing(GatewayRoute::getPriority));
        return candidates.get(0);
    }

    /**
     * Construit le message de raison du routage
     */
    private String buildRoutingReason(GatewayRoute route, boolean useStock) {
        StringBuilder sb = new StringBuilder();
        sb.append("Route: ").append(route.getSourceCountry())
                .append(" -> ").append(route.getDestCountry())
                .append(" via ").append(route.getGateway().getDisplayName());

        if (useStock) {
            sb.append(" (utilisation stock)");
        }

        sb.append(" | Priorité ").append(route.getPriority())
                .append(" | Frais gateway ").append(route.getGatewayFeePercent()).append("%");

        return sb.toString();
    }

    /**
     * Vérifie si une route existe pour un couple source/destination
     */
    public boolean hasRoute(Country source, Country dest) {
        return routeRepository.existsRoute(source, dest);
    }

    /**
     * Retourne toutes les routes disponibles pour un couple
     */
    public List<GatewayRoute> getAvailableRoutes(Country source, Country dest) {
        return routeRepository.findActiveRoutes(source, dest);
    }
}
