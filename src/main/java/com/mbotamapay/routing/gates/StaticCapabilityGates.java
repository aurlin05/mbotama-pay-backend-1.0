package com.mbotamapay.routing.gates;

import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.gateway.GatewayCapabilities;
import com.mbotamapay.routing.CapabilityMatrix;
import com.mbotamapay.routing.Eligibility.Candidate;
import com.mbotamapay.routing.Eligibility.Verdict;
import com.mbotamapay.routing.FxRegistry;
import com.mbotamapay.routing.RoutingContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Portes statiques : elles ne dépendent que du triplet
 * (passerelle, corridor, opérateur) et n'accèdent à aucune ressource externe.
 *
 * <p>
 * Regroupées ici parce qu'elles partagent la même source — la déclaration de
 * capacités portée par chaque passerelle — et qu'elles se lisent mieux les unes
 * à côté des autres.
 */
public final class StaticCapabilityGates {

    private StaticCapabilityGates() {
    }

    /** La route est-elle active ? Déjà filtré au chargement, revérifié ici. */
    @Component
    @Order(10)
    public static class RouteEnabled implements EligibilityGate {

        @Override
        public String name() {
            return "route-active";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            return Boolean.TRUE.equals(candidate.route().getEnabled())
                    ? Verdict.pass()
                    : Verdict.reject("route désactivée en configuration");
        }
    }

    /**
     * Une passerelle peut être déclarée en base sans implémentation Java, ou
     * implémentée sans être configurée. Les deux cas échouaient auparavant à
     * l'exécution, au moment du versement, par une {@code IllegalStateException}
     * levée au fond de l'orchestrateur.
     */
    @Component
    @Order(20)
    @RequiredArgsConstructor
    public static class GatewayOperational implements EligibilityGate {

        private final CapabilityMatrix matrix;

        @Override
        public String name() {
            return "passerelle-operationnelle";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            GatewayType gateway = candidate.gateway();
            if (!matrix.isImplemented(gateway)) {
                return Verdict.reject("aucune implémentation pour cette passerelle");
            }
            if (!matrix.isOperational(gateway)) {
                return Verdict.reject("passerelle non configurée ou non validée");
            }
            return Verdict.pass();
        }
    }

    /**
     * La passerelle verse-t-elle réellement dans le pays de destination ?
     *
     * <p>
     * {@code supportsPayoutTo()} existait sur les trois passerelles et n'était
     * appelé par personne : la seule garde était la table de routes, tenue à la
     * main dans les migrations. Une ligne erronée suffisait à envoyer un versement
     * vers un point d'accès inexistant.
     */
    @Component
    @Order(30)
    @RequiredArgsConstructor
    public static class PayoutCountrySupported implements EligibilityGate {

        private final CapabilityMatrix matrix;

        @Override
        public String name() {
            return "pays-payout";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            Optional<GatewayCapabilities> caps = matrix.capabilities(candidate.gateway());
            if (caps.isEmpty()) {
                return Verdict.reject("capacités non déclarées");
            }
            var dest = context.request().destCountry();
            return caps.get().canPayoutTo(dest)
                    ? Verdict.pass()
                    : Verdict.reject("ne verse pas vers " + dest.getDisplayName());
        }
    }

    /**
     * L'opérateur du bénéficiaire est-il joignable par cette passerelle ?
     * Auparavant pondéré à 10 % du score.
     */
    @Component
    @Order(40)
    @RequiredArgsConstructor
    public static class OperatorReachable implements EligibilityGate {

        private final CapabilityMatrix matrix;

        @Override
        public String name() {
            return "operateur-joignable";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            var operator = context.request().destOperator();
            if (operator == null) {
                // Opérateur indéterminé : on ne peut pas garantir l'acheminement.
                // Refuser explicitement vaut mieux qu'un versement au hasard.
                return Verdict.reject("opérateur du bénéficiaire non identifié");
            }
            Optional<GatewayCapabilities> caps = matrix.capabilities(candidate.gateway());
            if (caps.isEmpty()) {
                return Verdict.reject("capacités non déclarées");
            }
            return caps.get().canReach(operator)
                    ? Verdict.pass()
                    : Verdict.reject("ne dessert pas " + operator.getDisplayName());
        }
    }

    /**
     * Les devises du corridor sont-elles compatibles, et la passerelle les
     * manipule-t-elle ?
     *
     * <p>
     * Le flux écrivait {@code "XOF"} en dur partout, y compris pour la Guinée
     * (GNF) et la RDC (CDF), dont les ordres de grandeur n'ont rien à voir.
     */
    @Component
    @Order(50)
    @RequiredArgsConstructor
    public static class CurrencySupported implements EligibilityGate {

        private final CapabilityMatrix matrix;
        private final FxRegistry fx;

        @Override
        public String name() {
            return "devise";
        }

        @Override
        public Verdict test(Candidate candidate, GateContext context) {
            RoutingContext request = context.request();
            String from = request.sourceCurrency();
            String to = request.destCurrency();

            if (request.isCrossCurrency() && !fx.isConvertible(from, to)) {
                return Verdict.reject("aucun taux déclaré pour " + from + "→" + to);
            }

            Optional<GatewayCapabilities> caps = matrix.capabilities(candidate.gateway());
            if (caps.isEmpty()) {
                return Verdict.reject("capacités non déclarées");
            }
            if (!caps.get().handlesCurrency(to)) {
                return Verdict.reject("ne traite pas la devise " + to);
            }
            return Verdict.pass();
        }
    }
}
