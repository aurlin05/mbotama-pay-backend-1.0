package com.mbotamapay.gateway;

import com.mbotamapay.entity.GatewayCoverage;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.gateway.GatewayCapabilityRegistry.CoverageRequest;
import com.mbotamapay.repository.GatewayCoverageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Registre de couverture partenaire")
class GatewayCapabilityRegistryTest {

    private static final GatewayType GATEWAY = GatewayType.FEEXPAY;

    private static final GatewayCapabilities CODE_DEFAULT = new GatewayCapabilities(
            GATEWAY,
            EnumSet.of(Country.BENIN, Country.TOGO),
            EnumSet.of(Country.BENIN, Country.TOGO),
            Set.of("XOF"),
            EnumSet.of(MobileOperator.MTN_BJ, MobileOperator.MOOV_BJ),
            true);

    private InMemoryCoverageRepository repository;
    private GatewayCapabilityOverrides configOverrides;
    private GatewayCapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCoverageRepository();
        configOverrides = new GatewayCapabilityOverrides();
        registry = new GatewayCapabilityRegistry(repository, configOverrides);
        registry.load();
        registry.registerDefault(GATEWAY, CODE_DEFAULT);
    }

    @Nested
    @DisplayName("Chaîne de résolution")
    class Resolution {

        @Test
        @DisplayName("sans redéfinition, la déclaration du code s'applique")
        void codeDefaultApplies() {
            assertThat(registry.capabilities(GATEWAY, CODE_DEFAULT)).isEqualTo(CODE_DEFAULT);
            assertThat(registry.describe(GATEWAY).source()).isEqualTo("CODE");
        }

        @Test
        @DisplayName("la configuration prime sur le code")
        void configBeatsCode() {
            var declaration = new GatewayCapabilityOverrides.Declaration();
            declaration.setPayoutCountries(List.of("BENIN", "TOGO", "SENEGAL"));
            configOverrides.setCapabilities(Map.of("feexpay", declaration));
            registry.reload();

            assertThat(registry.capabilities(GATEWAY, CODE_DEFAULT).payoutCountries())
                    .containsExactlyInAnyOrder(Country.BENIN, Country.TOGO, Country.SENEGAL);
            assertThat(registry.describe(GATEWAY).source()).isEqualTo("CONFIG");
        }

        @Test
        @DisplayName("la base prime sur la configuration")
        void databaseBeatsConfig() {
            var declaration = new GatewayCapabilityOverrides.Declaration();
            declaration.setPayoutCountries(List.of("SENEGAL"));
            configOverrides.setCapabilities(Map.of("feexpay", declaration));
            registry.reload();

            registry.override(GATEWAY, new CoverageRequest(
                    List.of("COTE_DIVOIRE"), null, null, null, null, "test"), "admin");

            assertThat(registry.capabilities(GATEWAY, CODE_DEFAULT).payoutCountries())
                    .containsExactly(Country.COTE_DIVOIRE);
            assertThat(registry.describe(GATEWAY).source()).isEqualTo("DATABASE");
        }

        @Test
        @DisplayName("une redéfinition partielle laisse les autres champs au niveau inférieur")
        void partialOverrideKeepsOtherFields() {
            registry.override(GATEWAY, new CoverageRequest(
                    List.of("BENIN", "TOGO", "SENEGAL"), null, null, null, null, null), "admin");

            var effective = registry.capabilities(GATEWAY, CODE_DEFAULT);
            assertThat(effective.payoutCountries())
                    .containsExactlyInAnyOrder(Country.BENIN, Country.TOGO, Country.SENEGAL);
            // Opérateurs, devises et pays d'encaissement inchangés
            assertThat(effective.operators()).isEqualTo(CODE_DEFAULT.operators());
            assertThat(effective.currencies()).isEqualTo(CODE_DEFAULT.currencies());
            assertThat(effective.collectionCountries()).isEqualTo(CODE_DEFAULT.collectionCountries());
        }
    }

    @Nested
    @DisplayName("Effet immédiat")
    class Immediacy {

        @Test
        @DisplayName("une modification est visible sans redémarrage")
        void changeIsVisibleWithoutRestart() {
            // Lecture initiale : le cache est peuplé.
            assertThat(registry.capabilities(GATEWAY, CODE_DEFAULT).canPayoutTo(Country.SENEGAL))
                    .isFalse();

            registry.override(GATEWAY, new CoverageRequest(
                    List.of("BENIN", "TOGO", "SENEGAL"), null, null, null, null, null), "admin");

            // Le cache doit avoir été invalidé, sinon la modification n'aurait
            // d'effet qu'au redéploiement suivant.
            assertThat(registry.capabilities(GATEWAY, CODE_DEFAULT).canPayoutTo(Country.SENEGAL))
                    .isTrue();
        }

        @Test
        @DisplayName("la remise à zéro rend la main au code")
        void resetRestoresCodeDefault() {
            registry.override(GATEWAY, new CoverageRequest(
                    List.of("COTE_DIVOIRE"), null, null, null, null, null), "admin");
            assertThat(registry.describe(GATEWAY).source()).isEqualTo("DATABASE");

            registry.reset(GATEWAY, "admin");

            assertThat(registry.capabilities(GATEWAY, CODE_DEFAULT)).isEqualTo(CODE_DEFAULT);
            assertThat(registry.describe(GATEWAY).source()).isEqualTo("CODE");
            assertThat(repository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("vider tous les champs supprime la redéfinition")
        void clearingAllFieldsRemovesOverride() {
            registry.override(GATEWAY, new CoverageRequest(
                    List.of("COTE_DIVOIRE"), null, null, null, null, null), "admin");

            registry.override(GATEWAY, new CoverageRequest(
                    List.of(), List.of(), List.of(), List.of(), null, null), "admin");

            assertThat(repository.findAll()).isEmpty();
            assertThat(registry.describe(GATEWAY).source()).isEqualTo("CODE");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("un pays inconnu est refusé, pas ignoré")
        void unknownCountryRejected() {
            assertThatThrownBy(() -> registry.override(GATEWAY, new CoverageRequest(
                    List.of("BENIN", "NARNIA"), null, null, null, null, null), "admin"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("NARNIA");

            // Rien n'a été écrit : la validation précède l'écriture.
            assertThat(repository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("un opérateur inconnu est refusé")
        void unknownOperatorRejected() {
            assertThatThrownBy(() -> registry.override(GATEWAY, new CoverageRequest(
                    null, null, null, List.of("MTN_BJ", "PIGEON_VOYAGEUR"), null, null), "admin"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("PIGEON_VOYAGEUR");
        }

        @Test
        @DisplayName("un code devise mal formé est refusé")
        void malformedCurrencyRejected() {
            assertThatThrownBy(() -> registry.override(GATEWAY, new CoverageRequest(
                    null, null, List.of("XOF", "EUROS"), null, null, null), "admin"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("EUROS");
        }

        @Test
        @DisplayName("une passerelle non implémentée est refusée")
        void unknownGatewayRejected() {
            assertThatThrownBy(() -> registry.override(GatewayType.MONETBIL,
                    new CoverageRequest(List.of("CAMEROON"), null, null, null, null, null), "admin"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("non implémentée");
        }
    }

    @Test
    @DisplayName("la trace de modification est conservée")
    void auditTrailIsKept() {
        registry.override(GATEWAY, new CoverageRequest(
                List.of("SENEGAL"), null, null, null, null, "ouverture SN confirmée par le partenaire"),
                "42");

        var view = registry.describe(GATEWAY);
        assertThat(view.updatedBy()).isEqualTo("42");
        assertThat(view.note()).isEqualTo("ouverture SN confirmée par le partenaire");
        assertThat(view.updatedAt()).isNotNull();
        // L'écart avec la déclaration d'origine reste lisible.
        assertThat(view.codeDefaultPayoutCountries()).containsExactly("BENIN", "TOGO");
    }

    /**
     * Dépôt en mémoire : le registre n'a besoin que de findAll / save / deleteById,
     * et un vrai simulacre Mockito rendrait ces tests illisibles.
     */
    private static class InMemoryCoverageRepository
            implements GatewayCoverageRepository {

        private final Map<GatewayType, GatewayCoverage> store = new EnumMap<>(GatewayType.class);

        @Override
        public List<GatewayCoverage> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public <S extends GatewayCoverage> S save(S entity) {
            store.put(entity.getGateway(), entity);
            return entity;
        }

        @Override
        public void deleteById(GatewayType id) {
            store.remove(id);
        }

        @Override
        public Optional<GatewayCoverage> findById(GatewayType id) {
            return Optional.ofNullable(store.get(id));
        }

        // --- Reste du contrat JpaRepository, non utilisé ici ---

        @Override public void flush() { }
        @Override public <S extends GatewayCoverage> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends GatewayCoverage> List<S> saveAllAndFlush(Iterable<S> entities) {
            return saveAll(entities);
        }
        @Override public void deleteAllInBatch(Iterable<GatewayCoverage> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<GatewayType> ids) { }
        @Override public void deleteAllInBatch() { store.clear(); }
        @Override public GatewayCoverage getOne(GatewayType id) { return store.get(id); }
        @Override public GatewayCoverage getById(GatewayType id) { return store.get(id); }
        @Override public GatewayCoverage getReferenceById(GatewayType id) { return store.get(id); }
        @Override public <S extends GatewayCoverage> List<S> findAll(org.springframework.data.domain.Example<S> example) {
            return List.of();
        }
        @Override public <S extends GatewayCoverage> List<S> findAll(org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) {
            return List.of();
        }
        @Override public <S extends GatewayCoverage> List<S> saveAll(Iterable<S> entities) {
            List<S> saved = new ArrayList<>();
            entities.forEach(e -> saved.add(save(e)));
            return saved;
        }
        @Override public List<GatewayCoverage> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<GatewayCoverage> findAll(
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public List<GatewayCoverage> findAllById(Iterable<GatewayType> ids) { return List.of(); }
        @Override public long count() { return store.size(); }
        @Override public void deleteAll(Iterable<? extends GatewayCoverage> entities) { }
        @Override public void deleteAll() { store.clear(); }
        @Override public void delete(GatewayCoverage entity) { store.remove(entity.getGateway()); }
        @Override public void deleteAllById(Iterable<? extends GatewayType> ids) { }
        @Override public boolean existsById(GatewayType id) { return store.containsKey(id); }
        @Override public <S extends GatewayCoverage> Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) {
            return Optional.empty();
        }
        @Override public <S extends GatewayCoverage> org.springframework.data.domain.Page<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public <S extends GatewayCoverage> long count(org.springframework.data.domain.Example<S> example) {
            return 0;
        }
        @Override public <S extends GatewayCoverage> boolean exists(org.springframework.data.domain.Example<S> example) {
            return false;
        }
        @Override public <S extends GatewayCoverage, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            return null;
        }
    }
}
