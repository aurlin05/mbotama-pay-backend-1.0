package com.mbotamapay.gateway;

import com.mbotamapay.entity.GatewayCoverage;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.repository.GatewayCoverageRepository;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Source unique de la couverture effective d'une passerelle.
 *
 * <p>
 * Trois niveaux, du plus fort au plus faible :
 * <ol>
 * <li><strong>base</strong> — modifiable à chaud depuis l'administration ;</li>
 * <li><strong>configuration</strong> — {@code gateway.capabilities.*}, pour un
 * environnement figé ou un déploiement piloté par variables ;</li>
 * <li><strong>code</strong> — la déclaration portée par la passerelle
 * elle-même, qui reste le point de départ raisonnable.</li>
 * </ol>
 *
 * <p>
 * Chaque champ se résout indépendamment : une redéfinition qui ne porte que sur
 * les pays de versement laisse opérateurs et devises au niveau inférieur.
 *
 * <p>
 * Le résultat est mis en cache et invalidé à chaque écriture. Les passerelles
 * consultent le registre à chaque appel plutôt que de figer leur couverture au
 * démarrage : sans cela, une modification depuis l'administration n'aurait
 * d'effet qu'au redéploiement suivant, ce qui viderait l'exercice de son sens.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GatewayCapabilityRegistry {

    private final GatewayCoverageRepository repository;
    private final GatewayCapabilityOverrides configOverrides;

    // Ces trois tables sont lues et écrites depuis les fils de traitement des
    // requêtes : le cache résolu se remplit à la première lecture de chaque
    // passerelle, et se vide à chaque écriture d'administration. Une EnumMap
    // conviendrait pour un remplissage au seul démarrage, pas ici.

    /** Déclarations portées par le code, enregistrées au démarrage. */
    private final Map<GatewayType, GatewayCapabilities> codeDefaults = new ConcurrentHashMap<>();

    /** Redéfinitions en base, rechargées à chaque écriture. */
    private volatile Map<GatewayType, GatewayCoverage> stored = Map.of();

    /** Résultat de la chaîne, invalidé avec {@link #stored}. */
    private volatile Map<GatewayType, GatewayCapabilities> resolved = new ConcurrentHashMap<>();

    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * Enregistre la déclaration codée d'une passerelle. Appelé par chaque
     * passerelle au démarrage.
     */
    public void registerDefault(GatewayType gateway, GatewayCapabilities defaults) {
        codeDefaults.put(gateway, defaults);
        resolved.remove(gateway);
    }

    /**
     * Couverture effective. Retombe sur la déclaration codée si la passerelle ne
     * s'est pas encore enregistrée.
     */
    public GatewayCapabilities capabilities(GatewayType gateway, GatewayCapabilities codeDefault) {
        codeDefaults.putIfAbsent(gateway, codeDefault);
        GatewayCapabilities cached = resolved.get(gateway);
        if (cached != null) {
            return cached;
        }
        GatewayCapabilities computed = compute(gateway, codeDefault);
        resolved.put(gateway, computed);
        return computed;
    }

    // ==================================================================
    // Lecture pour l'administration
    // ==================================================================

    /**
     * Couverture effective de chaque passerelle, avec l'origine de chaque champ.
     * C'est cette origine qui permet de savoir si une valeur vient d'une décision
     * d'exploitation ou d'un défaut jamais revu.
     */
    public List<CoverageView> describeAll() {
        return codeDefaults.keySet().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(this::describe)
                .toList();
    }

    public CoverageView describe(GatewayType gateway) {
        GatewayCapabilities codeDefault = codeDefaults.get(gateway);
        if (codeDefault == null) {
            throw new BadRequestException("Passerelle inconnue ou non implémentée : " + gateway);
        }
        GatewayCapabilities configLevel = configOverrides.resolve(gateway, codeDefault);
        GatewayCoverage dbLevel = stored.get(gateway);
        GatewayCapabilities effective = capabilities(gateway, codeDefault);

        return CoverageView.builder()
                .gateway(gateway)
                .displayName(gateway.getDisplayName())
                .payoutCountries(names(effective.payoutCountries()))
                .collectionCountries(names(effective.collectionCountries()))
                .currencies(new TreeSet<>(effective.currencies()))
                .operators(names(effective.operators()))
                .supportsPayout(effective.supportsPayout())
                .source(sourceOf(dbLevel, configLevel, codeDefault))
                .note(dbLevel == null ? null : dbLevel.getNote())
                .updatedAt(dbLevel == null ? null : dbLevel.getUpdatedAt())
                .updatedBy(dbLevel == null ? null : dbLevel.getUpdatedBy())
                .codeDefaultPayoutCountries(names(codeDefault.payoutCountries()))
                .build();
    }

    // ==================================================================
    // Écriture depuis l'administration
    // ==================================================================

    /**
     * Enregistre une redéfinition. Les champs nuls ou vides sont laissés au
     * niveau inférieur ; les valeurs inconnues sont refusées plutôt qu'ignorées.
     */
    @Transactional
    public CoverageView override(GatewayType gateway, CoverageRequest request, String actor) {
        if (!codeDefaults.containsKey(gateway)) {
            throw new BadRequestException("Passerelle inconnue ou non implémentée : " + gateway);
        }

        // Valider d'abord, écrire ensuite : une liste à moitié appliquée
        // laisserait une couverture incohérente en base.
        validateCountries(request.payoutCountries(), "payoutCountries");
        validateCountries(request.collectionCountries(), "collectionCountries");
        validateOperators(request.operators());
        validateCurrencies(request.currencies());

        GatewayCoverage coverage = stored.getOrDefault(gateway,
                GatewayCoverage.builder().gateway(gateway).build());

        if (request.payoutCountries() != null) {
            coverage.setPayoutCountries(join(request.payoutCountries()));
        }
        if (request.collectionCountries() != null) {
            coverage.setCollectionCountries(join(request.collectionCountries()));
        }
        if (request.operators() != null) {
            coverage.setOperators(join(request.operators()));
        }
        if (request.currencies() != null) {
            coverage.setCurrencies(join(request.currencies()));
        }
        if (request.supportsPayout() != null) {
            coverage.setSupportsPayout(request.supportsPayout());
        }
        coverage.setNote(request.note());
        coverage.setUpdatedAt(Instant.now());
        coverage.setUpdatedBy(actor);

        if (coverage.isEmpty()) {
            // Tous les champs ont été vidés : c'est une remise à zéro.
            repository.deleteById(gateway);
            log.info("Gateway {} coverage override removed by {} (all fields cleared)", gateway, actor);
        } else {
            repository.save(coverage);
            log.info("Gateway {} coverage overridden by {}: payout={}, operators={}, note={}",
                    gateway, actor, coverage.getPayoutCountries(),
                    coverage.getOperators() == null ? "inchangés" : "redéfinis", request.note());
        }

        reload();
        return describe(gateway);
    }

    /** Supprime la redéfinition : la configuration, puis le code, reprennent la main. */
    @Transactional
    public CoverageView reset(GatewayType gateway, String actor) {
        if (!codeDefaults.containsKey(gateway)) {
            throw new BadRequestException("Passerelle inconnue ou non implémentée : " + gateway);
        }
        repository.deleteById(gateway);
        log.info("Gateway {} coverage reset to configuration/code defaults by {}", gateway, actor);
        reload();
        return describe(gateway);
    }

    /** Recharge les redéfinitions depuis la base et vide le cache résolu. */
    public void reload() {
        Map<GatewayType, GatewayCoverage> fresh = new ConcurrentHashMap<>();
        repository.findAll().forEach(c -> fresh.put(c.getGateway(), c));
        this.stored = Map.copyOf(fresh);
        this.resolved = new ConcurrentHashMap<>();
        if (!fresh.isEmpty()) {
            log.info("Gateway coverage overrides loaded from database for: {}", fresh.keySet());
        }
    }

    // ==================================================================
    // Internes
    // ==================================================================

    private GatewayCapabilities compute(GatewayType gateway, GatewayCapabilities codeDefault) {
        // Niveau 2 : configuration
        GatewayCapabilities base = configOverrides.resolve(gateway, codeDefault);

        // Niveau 1 : base
        GatewayCoverage override = stored.get(gateway);
        if (override == null) {
            return base;
        }

        return new GatewayCapabilities(
                gateway,
                parseCountries(override.getCollectionCountries(), base.collectionCountries()),
                parseCountries(override.getPayoutCountries(), base.payoutCountries()),
                parseCurrencies(override.getCurrencies(), base.currencies()),
                parseOperators(override.getOperators(), base.operators()),
                override.getSupportsPayout() == null ? base.supportsPayout() : override.getSupportsPayout());
    }

    private String sourceOf(GatewayCoverage dbLevel, GatewayCapabilities configLevel,
            GatewayCapabilities codeDefault) {
        if (dbLevel != null) {
            return "DATABASE";
        }
        return configLevel.equals(codeDefault) ? "CODE" : "CONFIG";
    }

    private Set<Country> parseCountries(String csv, Set<Country> fallback) {
        if (csv == null || csv.isBlank()) {
            return fallback;
        }
        Set<Country> parsed = EnumSet.noneOf(Country.class);
        for (String token : csv.split(",")) {
            enumValue(Country.class, token).ifPresent(parsed::add);
        }
        return parsed;
    }

    private Set<MobileOperator> parseOperators(String csv, Set<MobileOperator> fallback) {
        if (csv == null || csv.isBlank()) {
            return fallback;
        }
        Set<MobileOperator> parsed = EnumSet.noneOf(MobileOperator.class);
        for (String token : csv.split(",")) {
            enumValue(MobileOperator.class, token).ifPresent(parsed::add);
        }
        return parsed;
    }

    private Set<String> parseCurrencies(String csv, Set<String> fallback) {
        if (csv == null || csv.isBlank()) {
            return fallback;
        }
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateCountries(List<String> values, String field) {
        if (values == null) {
            return;
        }
        List<String> unknown = values.stream()
                .filter(v -> enumValue(Country.class, v).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException(field + " : pays inconnu(s) " + String.join(", ", unknown)
                    + ". Valeurs acceptées : " + Arrays.stream(Country.values())
                            .map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    private void validateOperators(List<String> values) {
        if (values == null) {
            return;
        }
        List<String> unknown = values.stream()
                .filter(v -> enumValue(MobileOperator.class, v).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException("operators : opérateur(s) inconnu(s) "
                    + String.join(", ", unknown));
        }
    }

    private void validateCurrencies(List<String> values) {
        if (values == null) {
            return;
        }
        List<String> invalid = values.stream()
                .map(String::trim)
                .filter(v -> !v.matches("[A-Za-z]{3}"))
                .toList();
        if (!invalid.isEmpty()) {
            throw new BadRequestException("currencies : code(s) ISO invalide(s) "
                    + String.join(", ", invalid));
        }
    }

    private <E extends Enum<E>> Optional<E> enumValue(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String join(List<String> values) {
        return values.isEmpty() ? null
                : values.stream().map(s -> s.trim().toUpperCase()).collect(Collectors.joining(","));
    }

    private <E extends Enum<E>> SortedSet<String> names(Set<E> values) {
        return values.stream().map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    // ==================================================================
    // Types d'échange
    // ==================================================================

    /**
     * Redéfinition demandée. Un champ {@code null} n'est pas touché ; une liste
     * vide efface la redéfinition de ce champ.
     */
    public record CoverageRequest(
            List<String> payoutCountries,
            List<String> collectionCountries,
            List<String> currencies,
            List<String> operators,
            Boolean supportsPayout,
            String note) {
    }

    @Builder
    public record CoverageView(
            GatewayType gateway,
            String displayName,
            SortedSet<String> payoutCountries,
            SortedSet<String> collectionCountries,
            SortedSet<String> currencies,
            SortedSet<String> operators,
            boolean supportsPayout,
            /** CODE, CONFIG ou DATABASE — d'où vient la couverture appliquée. */
            String source,
            String note,
            Instant updatedAt,
            String updatedBy,
            /** Pour situer l'écart avec la déclaration d'origine. */
            SortedSet<String> codeDefaultPayoutCountries) {
    }
}
