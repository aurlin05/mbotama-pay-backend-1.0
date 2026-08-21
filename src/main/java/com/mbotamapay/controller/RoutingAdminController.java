package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.routing.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Administration du moteur de routage.
 *
 * <p>
 * Remplace l'ancien contrôleur d'orchestration, qui était injoignable pour trois
 * raisons cumulées : son chemin répétait le contexte applicatif
 * ({@code /api/v1/api/admin/...}), le motif de sécurité {@code /admin/**} ne
 * correspondait donc à aucune route, et surtout aucun compte ne s'est jamais vu
 * attribuer {@code ROLE_ADMIN} — l'entité utilisateur n'a pas de champ de rôle.
 *
 * <p>
 * <strong>Ce contrôleur reste inaccessible tant qu'un rôle applicatif n'est pas
 * introduit.</strong> Le chemin et la protection sont maintenant corrects ; il
 * manque la brique d'habilitation, hors du périmètre du moteur.
 */
@RestController
@RequestMapping("/admin/routing")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Routing admin", description = "Supervision et pilotage du moteur de routage")
public class RoutingAdminController {

    private final RoutingEngine engine;
    private final CapabilityMatrix matrix;
    private final GatewayHealthMonitor health;
    private final RoutingPolicy policy;
    private final BridgeRouter bridgeRouter;
    private final CapabilityConsistencyValidator validator;

    @GetMapping("/health")
    @Operation(summary = "État des disjoncteurs et métriques par passerelle")
    public ResponseEntity<ApiResponse<Map<GatewayType, GatewayHealthMonitor.Metrics>>> health() {
        return ResponseEntity.ok(ApiResponse.success(health.allMetrics()));
    }

    @GetMapping("/consistency")
    @Operation(summary = "Contrôle de cohérence entre routes, capacités et opérateurs",
            description = "Le même contrôle est exécuté au démarrage. Le rejouer ici permet de "
                    + "vérifier l'effet d'une modification de routes sans redémarrer.")
    public ResponseEntity<ApiResponse<CapabilityConsistencyValidator.Report>> consistency() {
        return ResponseEntity.ok(ApiResponse.success(validator.run()));
    }

    @GetMapping("/matrix")
    @Operation(summary = "Graphe de routage chargé en mémoire")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matrix() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "routeCount", matrix.routeCount(),
                "corridors", matrix.corridors().size(),
                "gateways", matrix.allGateways().stream()
                        .map(g -> Map.of(
                                "gateway", g.getGatewayType().name(),
                                "operational", g.isOperational(),
                                "payoutCountries", g.capabilities().payoutCountries(),
                                "currencies", g.capabilities().currencies()))
                        .toList())));
    }

    @PostMapping("/matrix/refresh")
    @Operation(summary = "Recharge le graphe après modification des routes en base")
    public ResponseEntity<ApiResponse<String>> refresh() {
        matrix.refresh();
        return ResponseEntity.ok(ApiResponse.success("Graphe rechargé", "OK"));
    }

    /**
     * Rejoue une décision de routage sans rien exécuter.
     *
     * <p>
     * Renvoie la décision complète : candidats notés, routes écartées avec leur
     * motif porte par porte, dérogations appliquées. C'est l'outil de diagnostic
     * qui manquait — un corridor fermé ne se traduisait que par « aucune route
     * viable (toutes sous le seuil de score) ».
     */
    @GetMapping("/simulate")
    @Operation(summary = "Simule une décision de routage")
    public ResponseEntity<ApiResponse<RoutingDecision>> simulate(
            @RequestParam String senderPhone,
            @RequestParam String recipientPhone,
            @RequestParam long amount) {
        RoutingContext context = engine.contextFor(senderPhone, recipientPhone, amount);
        return ResponseEntity.ok(ApiResponse.success(engine.decide(context)));
    }

    @GetMapping("/bridges")
    @Operation(summary = "Ponts possibles entre deux pays")
    public ResponseEntity<ApiResponse<List<BridgeRouter.BridgeRoute>>> bridges(
            @RequestParam String source,
            @RequestParam String dest) {
        Country from = Country.fromIsoCode(source).orElseThrow();
        Country to = Country.fromIsoCode(dest).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(bridgeRouter.findAll(from, to)));
    }

    // === Politique d'exploitation ===

    @PostMapping("/gateways/{gateway}/suspend")
    @Operation(summary = "Suspend une passerelle", description = "Effet immédiat : la porte "
            + "d'éligibilité l'écarte, y compris sur les ponts.")
    public ResponseEntity<ApiResponse<String>> suspend(
            @PathVariable GatewayType gateway,
            @RequestParam(required = false, defaultValue = "manuel") String reason) {
        policy.suspend(gateway, reason);
        return ResponseEntity.ok(ApiResponse.success("Passerelle suspendue", gateway.name()));
    }

    @PostMapping("/gateways/{gateway}/resume")
    @Operation(summary = "Réactive une passerelle suspendue")
    public ResponseEntity<ApiResponse<String>> resume(@PathVariable GatewayType gateway) {
        policy.resume(gateway);
        return ResponseEntity.ok(ApiResponse.success("Passerelle réactivée", gateway.name()));
    }

    @PostMapping("/gateways/{gateway}/reset-circuit")
    @Operation(summary = "Referme manuellement le disjoncteur")
    public ResponseEntity<ApiResponse<String>> resetCircuit(@PathVariable GatewayType gateway) {
        health.reset(gateway);
        return ResponseEntity.ok(ApiResponse.success("Disjoncteur réinitialisé", gateway.name()));
    }

    @GetMapping("/policy")
    @Operation(summary = "Politique d'exploitation en vigueur")
    public ResponseEntity<ApiResponse<Map<String, Object>>> policy() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "suspended", policy.suspendedGateways(),
                "corridorPreferences", policy.allPreferences())));
    }
}
