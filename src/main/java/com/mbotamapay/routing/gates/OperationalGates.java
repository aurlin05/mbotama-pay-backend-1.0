package com.mbotamapay.routing.gates;

import com.mbotamapay.config.TransactionLimitsConfig;
import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.routing.Eligibility.Candidate;
import com.mbotamapay.routing.Eligibility.Verdict;
import com.mbotamapay.routing.GatewayHealthMonitor;
import com.mbotamapay.routing.RoutingContext;
import com.mbotamapay.routing.RoutingPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Portes dynamiques : elles dépendent de l'état vivant du système — santé des
 * partenaires, décisions d'exploitation, liquidité, plafonds réglementaires.
 */
public final class OperationalGates {

    private OperationalGates() {
    }

    /**
     * Le disjoncteur autorise-t-il cette passerelle ?
     *
     * <p>
     * Lecture pure : la porte ne consomme pas le droit de sonde. C'est
     * l'exécuteur qui réservera la sonde au moment de l'appel réel, sinon une
     * simple prévisualisation griller ait la tentative de récupération.
     */
    @Component
    @Order(60)
    @RequiredArgsConstructor
    public static class CircuitClosed implements EligibilityGate {

        private final GatewayHealthMonitor health;

        @Override
        public String name() {
            return "disjoncteur";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            return health.isAvailable(candidate.gateway())
                    ? Verdict.pass()
                    : Verdict.reject("disjoncteur ouvert (trop d'échecs récents)");
        }
    }

    /**
     * Suspension décidée par l'exploitation.
     *
     * <p>
     * La liste de suspension existait déjà mais n'était consultée par aucun
     * composant de routage : suspendre une passerelle n'avait aucun effet.
     */
    @Component
    @Order(70)
    @RequiredArgsConstructor
    public static class NotSuspended implements EligibilityGate {

        private final RoutingPolicy policy;

        @Override
        public String name() {
            return "suspension";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            return policy.isSuspended(candidate.gateway())
                    ? Verdict.reject("passerelle suspendue par l'exploitation")
                    : Verdict.pass();
        }
    }

    /**
     * Le corridor est-il ouvert et le montant sous son plafond ?
     *
     * <p>
     * Ce contrôle était évalué <em>après</em> l'orchestration, dans un autre
     * service : le moteur élisait donc une route sur un corridor fermé avant que
     * tout soit rejeté. En porte, un corridor fermé ne produit simplement aucun
     * candidat.
     */
    @Component
    @Order(80)
    @RequiredArgsConstructor
    public static class CorridorOpen implements EligibilityGate {

        private final TransactionLimitsConfig limits;

        @Override
        public String name() {
            return "corridor";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            RoutingContext request = context.request();
            var corridor = limits.findCorridor(
                    request.sourceCountry().getIsoCode(),
                    request.destCountry().getIsoCode());

            if (corridor.isEmpty()) {
                return limits.isRejectUnknownCorridors()
                        ? Verdict.reject("corridor " + request.corridor() + " non déclaré")
                        : Verdict.pass();
            }

            var limit = corridor.get();
            if (!Boolean.TRUE.equals(limit.getEnabled())) {
                return Verdict.reject("corridor " + request.corridor() + " désactivé");
            }
            if (limit.getMaxPerTransaction() != null && request.amount() > limit.getMaxPerTransaction()) {
                return Verdict.reject(String.format(
                        "montant au-dessus du plafond corridor (%,d)", limit.getMaxPerTransaction()));
            }
            return Verdict.pass();
        }
    }

    /**
     * Liquidité disponible dans le pays de destination.
     *
     * <p>
     * Auparavant pondéré à 15 % du score : une passerelle sans solde perdait
     * quinze points et pouvait rester élue.
     *
     * <p>
     * Sémantique de l'absence de stock : <strong>la porte passe</strong>. Un stock
     * non configuré signifie que la passerelle finance sur son propre flottant,
     * ce qui est le cas nominal quand on ne préfinance pas. Les deux composants
     * qui interrogeaient le stock donnaient auparavant deux réponses différentes
     * dans ce cas — note neutre d'un côté, feu vert de l'autre.
     */
    @Component
    @Order(90)
    public static class SufficientLiquidity implements EligibilityGate {

        @Value("${routing.liquidity.require-declared-stock:false}")
        private boolean requireDeclaredStock;

        @Override
        public String name() {
            return "liquidite";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            GatewayStock stock = context.destStocks().get(candidate.gateway());

            if (stock == null) {
                return requireDeclaredStock
                        ? Verdict.reject("aucun stock déclaré pour ce pays")
                        : Verdict.pass();
            }
            if (!stock.hasSufficientBalance(context.request().amount())) {
                return Verdict.reject(String.format(
                        "liquidité insuffisante (%,d disponible)", stock.getBalance()));
            }
            return Verdict.pass();
        }
    }
}
