package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.AuditAction;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.GatewayCapabilityRegistry;
import com.mbotamapay.routing.*;
import com.mbotamapay.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    private final GatewayCapabilityRegistry registry;
    private final AuditService auditService;

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

    // ==================================================================
    // Couverture partenaire
    // ==================================================================

    /**
     * Couverture effective de chaque passerelle, avec l'origine de la valeur
     * appliquée : {@code CODE}, {@code CONFIG} ou {@code DATABASE}.
     *
     * <p>
     * L'origine compte autant que la valeur : elle dit si la couverture résulte
     * d'une décision d'exploitation ou d'un défaut jamais revu — c'est
     * exactement la confusion qui avait laissé FeexPay annoncer six pays dans son
     * commentaire et n'en déclarer que quatre dans son code.
     */
    @GetMapping("/coverage")
    @Operation(summary = "Couverture effective de toutes les passerelles")
    public ResponseEntity<ApiResponse<List<GatewayCapabilityRegistry.CoverageView>>> coverage() {
        return ResponseEntity.ok(ApiResponse.success(registry.describeAll()));
    }

    @GetMapping("/coverage/{gateway}")
    @Operation(summary = "Couverture effective d'une passerelle")
    public ResponseEntity<ApiResponse<GatewayCapabilityRegistry.CoverageView>> coverage(
            @PathVariable GatewayType gateway) {
        return ResponseEntity.ok(ApiResponse.success(registry.describe(gateway)));
    }

    /**
     * Valeurs acceptées, pour qu'une interface d'administration n'ait pas à les
     * recopier : pays, devises, et opérateurs regroupés par pays.
     */
    @GetMapping("/coverage/reference")
    @Operation(summary = "Valeurs acceptées pour une redéfinition de couverture")
    public ResponseEntity<ApiResponse<Map<String, Object>>> coverageReference() {
        Map<String, List<String>> operatorsByCountry = new LinkedHashMap<>();
        Set<String> currencies = new TreeSet<>();
        for (Country country : Country.values()) {
            operatorsByCountry.put(country.name(), MobileOperator.getOperatorsForCountry(country)
                    .stream().map(Enum::name).sorted().toList());
            currencies.add(country.getCurrency());
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "countries", Arrays.stream(Country.values())
                        .map(c -> Map.of("name", c.name(), "iso", c.getIsoCode(),
                                "currency", c.getCurrency(), "label", c.getDisplayName()))
                        .toList(),
                "currencies", currencies,
                "operatorsByCountry", operatorsByCountry,
                "gateways", Arrays.stream(GatewayType.values()).map(Enum::name).toList())));
    }

    /**
     * Redéfinit la couverture d'une passerelle, avec effet immédiat.
     *
     * <p>
     * Les champs omis ou nuls sont laissés inchangés ; une liste vide efface la
     * redéfinition de ce champ et rend la main à la configuration puis au code.
     * Une valeur inconnue est refusée, pas ignorée en silence.
     *
     * <p>
     * La réponse porte le <strong>contrôle de cohérence rejoué</strong> : fermer
     * un pays encore desservi par des routes actives, ou en ouvrir un sans route,
     * se voit immédiatement plutôt qu'au prochain démarrage.
     */
    @PutMapping("/coverage/{gateway}")
    @Operation(summary = "Redéfinir la couverture d'une passerelle")
    public ResponseEntity<ApiResponse<CoverageChange>> updateCoverage(
            @PathVariable GatewayType gateway,
            @RequestBody GatewayCapabilityRegistry.CoverageRequest request,
            @AuthenticationPrincipal User actor) {

        String actorLabel = actor == null ? "system" : String.valueOf(actor.getId());
        var view = registry.override(gateway, request, actorLabel);

        auditService.log(AuditAction.ADMIN_GATEWAY_COVERAGE_UPDATED, String.format(
                "Couverture %s redéfinie par %s — payout=%s, note=%s",
                gateway, actorLabel, view.payoutCountries(), request.note()));

        return ResponseEntity.ok(ApiResponse.success(
                "Couverture mise à jour", changeResult(gateway, view)));
    }

    /** Supprime la redéfinition : la configuration, puis le code, reprennent la main. */
    @DeleteMapping("/coverage/{gateway}")
    @Operation(summary = "Rétablir la couverture par défaut d'une passerelle")
    public ResponseEntity<ApiResponse<CoverageChange>> resetCoverage(
            @PathVariable GatewayType gateway,
            @AuthenticationPrincipal User actor) {

        String actorLabel = actor == null ? "system" : String.valueOf(actor.getId());
        var view = registry.reset(gateway, actorLabel);

        auditService.log(AuditAction.ADMIN_GATEWAY_COVERAGE_RESET,
                String.format("Couverture %s rétablie par défaut par %s", gateway, actorLabel));

        return ResponseEntity.ok(ApiResponse.success(
                "Couverture rétablie", changeResult(gateway, view)));
    }

    /**
     * Recharge le graphe et rejoue le contrôle de cohérence, en isolant ce qui
     * concerne la passerelle modifiée.
     */
    private CoverageChange changeResult(GatewayType gateway, GatewayCapabilityRegistry.CoverageView view) {
        matrix.refresh();
        var report = validator.run();
        String label = gateway.getDisplayName();
        return new CoverageChange(
                view,
                report.errors().stream().filter(e -> e.startsWith(label)).toList(),
                report.warnings().stream().filter(w -> w.startsWith(label)).toList(),
                report.errors().size(),
                report.warnings().size());
    }

    /**
     * Résultat d'un changement de couverture : la nouvelle vue, plus ce que le
     * contrôle de cohérence en dit — d'abord pour la passerelle touchée, puis
     * globalement.
     */
    public record CoverageChange(
            GatewayCapabilityRegistry.CoverageView coverage,
            List<String> gatewayErrors,
            List<String> gatewayWarnings,
            int totalErrors,
            int totalWarnings) {
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
