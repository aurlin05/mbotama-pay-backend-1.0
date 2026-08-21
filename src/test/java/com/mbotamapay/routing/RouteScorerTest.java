package com.mbotamapay.routing;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.routing.Eligibility.Candidate;
import com.mbotamapay.service.FeeCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Scoring des routes")
class RouteScorerTest {

    private GatewayHealthMonitor health;
    private RouteScorer scorer;

    @BeforeEach
    void setUp() {
        health = new GatewayHealthMonitor();
        ReflectionTestUtils.setField(health, "failureThreshold", 5);
        ReflectionTestUtils.setField(health, "recoveryTimeoutMs", 300_000L);
        ReflectionTestUtils.setField(health, "windowMinutes", 60);

        FeeCalculator feeCalculator = new FeeCalculator();
        ReflectionTestUtils.setField(feeCalculator, "appFeePercent", new BigDecimal("2.0"));
        ReflectionTestUtils.setField(feeCalculator, "maxTotalPercent", new BigDecimal("7.0"));
        ReflectionTestUtils.setField(feeCalculator, "minFeeAmount", 100L);
        ReflectionTestUtils.setField(feeCalculator, "minFeeByCurrency", java.util.Map.of());

        FxRegistry fx = new FxRegistry();

        scorer = new RouteScorer(health, feeCalculator, fx);
        setWeights(35, 20, 30, 15);
    }

    private void setWeights(int cost, int margin, int reliability, int latency) {
        ReflectionTestUtils.setField(scorer, "weightCost", cost);
        ReflectionTestUtils.setField(scorer, "weightMargin", margin);
        ReflectionTestUtils.setField(scorer, "weightReliability", reliability);
        ReflectionTestUtils.setField(scorer, "weightLatency", latency);
        ReflectionTestUtils.setField(scorer, "priorAlpha", 1.0d);
        ReflectionTestUtils.setField(scorer, "priorBeta", 4.0d);
        ReflectionTestUtils.setField(scorer, "latencyCeilingMs", 10_000L);
        ReflectionTestUtils.setField(scorer, "coldStartLatency", 20);
    }

    private Candidate route(GatewayType gateway, String feePercent) {
        return new Candidate(GatewayRoute.builder()
                .sourceCountry(Country.SENEGAL)
                .destCountry(Country.SENEGAL)
                .gateway(gateway)
                .priority(1)
                .gatewayFeePercent(new BigDecimal(feePercent))
                .enabled(true)
                .build());
    }

    private RoutingContext request(long amount) {
        return RoutingContext.builder()
                .sourceCountry(Country.SENEGAL)
                .destCountry(Country.SENEGAL)
                .destOperator(MobileOperator.ORANGE_SN)
                .amount(amount)
                .senderPhone("+221770000001")
                .recipientPhone("+221770000002")
                .build();
    }

    @Test
    @DisplayName("refuse de démarrer si les poids ne totalisent pas 100")
    void rejectsWeightsThatDoNotSumTo100() {
        setWeights(35, 20, 30, 20); // 105
        assertThatThrownBy(() -> scorer.validateWeights())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("totaliser 100");
    }

    @Test
    @DisplayName("une passerelle jamais utilisée ne bat plus une passerelle éprouvée")
    void provenGatewayBeatsUnknownGateway() {
        // Même tarif : seule l'expérience accumulée les sépare.
        Candidate unknown = route(GatewayType.PAYDUNYA, "2.70");
        Candidate proven = route(GatewayType.CINETPAY, "2.70");

        for (int i = 0; i < 480; i++) {
            health.recordSuccess(GatewayType.CINETPAY, 2_500);
        }
        for (int i = 0; i < 20; i++) {
            health.recordFailure(GatewayType.CINETPAY, 2_500, "boom");
        }

        List<ScoredRoute> ranked = scorer.rank(List.of(unknown, proven), request(100_000));

        // Avec les anciens défauts — fiabilité 100 et vitesse 80 en l'absence de
        // données — l'inconnue passait devant.
        assertThat(ranked.get(0).gateway()).isEqualTo(GatewayType.CINETPAY);
        assertThat(ranked.get(0).reliabilityScore())
                .isGreaterThan(ranked.get(1).reliabilityScore());
    }

    @Test
    @DisplayName("le prior part bas et converge avec les preuves")
    void priorConvergesWithEvidence() {
        Candidate candidate = route(GatewayType.FEEXPAY, "2.70");
        RoutingContext request = request(100_000);

        int coldScore = scorer.rank(List.of(candidate), request).get(0).reliabilityScore();
        assertThat(coldScore).isEqualTo(20); // (0 + 1) / (0 + 5)

        for (int i = 0; i < 48; i++) {
            health.recordSuccess(GatewayType.FEEXPAY, 1_000);
        }
        for (int i = 0; i < 2; i++) {
            health.recordFailure(GatewayType.FEEXPAY, 1_000, "boom");
        }
        int warmScore = scorer.rank(List.of(candidate), request).get(0).reliabilityScore();
        assertThat(warmScore).isEqualTo(89); // (48 + 1) / (50 + 5)
    }

    @Test
    @DisplayName("le coût est mesuré en argent, pas en pourcentage")
    void costIsMeasuredInMoneyNotPercent() {
        // Sous le plancher de frais, deux tarifs différents coûtent la même chose à
        // l'expéditeur. L'ancien score les départageait quand même sur le
        // pourcentage.
        Candidate cheapPercent = route(GatewayType.FEEXPAY, "2.70");
        Candidate dearPercent = route(GatewayType.CINETPAY, "4.00");

        List<ScoredRoute> ranked = scorer.rank(
                List.of(cheapPercent, dearPercent), request(1_000));

        assertThat(ranked).allSatisfy(r -> assertThat(r.fees().getTotalFee()).isEqualTo(100L));
        assertThat(ranked.get(0).costScore()).isEqualTo(ranked.get(1).costScore());
    }

    @Test
    @DisplayName("sur un montant réel, la route la moins chère prend le meilleur score de coût")
    void cheapestRouteScoresBestOnCost() {
        List<ScoredRoute> ranked = scorer.rank(
                List.of(route(GatewayType.FEEXPAY, "2.70"), route(GatewayType.CINETPAY, "4.00")),
                request(100_000));

        ScoredRoute feexpay = ranked.stream()
                .filter(r -> r.gateway() == GatewayType.FEEXPAY).findFirst().orElseThrow();
        ScoredRoute cinetpay = ranked.stream()
                .filter(r -> r.gateway() == GatewayType.CINETPAY).findFirst().orElseThrow();

        assertThat(feexpay.fees().getTotalFee()).isLessThan(cinetpay.fees().getTotalFee());
        assertThat(feexpay.costScore()).isEqualTo(100);
        assertThat(cinetpay.costScore()).isLessThan(100);
    }

    @Test
    @DisplayName("une marge négative ne peut pas obtenir de points de marge")
    void negativeMarginScoresZero() {
        // Passerelle à 8 % : le plafond de 7 % s'applique, la plateforme paie la
        // différence. appFee était écrêté à zéro et masquait la perte.
        List<ScoredRoute> ranked = scorer.rank(
                List.of(route(GatewayType.CINETPAY, "8.00")), request(100_000));

        assertThat(ranked.get(0).netMargin()).isNegative();
        assertThat(ranked.get(0).marginScore()).isZero();
    }

    @Test
    @DisplayName("le classement est déterministe à score égal")
    void rankingIsDeterministic() {
        List<Candidate> candidates = List.of(
                route(GatewayType.PAYTECH, "3.00"),
                route(GatewayType.CINETPAY, "3.00"),
                route(GatewayType.FEEXPAY, "3.00"));

        List<String> first = scorer.rank(candidates, request(50_000)).stream()
                .map(r -> r.gateway().name()).toList();
        List<String> second = scorer.rank(candidates, request(50_000)).stream()
                .map(r -> r.gateway().name()).toList();

        assertThat(first).isEqualTo(second);
    }
}
