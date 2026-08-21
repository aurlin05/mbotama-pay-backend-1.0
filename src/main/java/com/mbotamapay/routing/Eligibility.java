package com.mbotamapay.routing;

import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.GatewayType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Types de la phase 1 du moteur : l'éligibilité.
 *
 * <p>
 * Une porte répond par oui ou par non. Elle ne pondère rien. Un refus produit
 * toujours un motif, et ce motif est conservé jusque dans la décision
 * persistée — c'est ce qui permet de répondre à « pourquoi ce transfert n'est
 * pas passé » autrement que par un score.
 */
public final class Eligibility {

    private Eligibility() {
    }

    /** Une route candidate, avant tout jugement. */
    public record Candidate(GatewayRoute route) {
        public GatewayType gateway() {
            return route.getGateway();
        }
    }

    /** Réponse d'une porte. */
    public record Verdict(boolean eligible, String reason) {

        private static final Verdict PASS = new Verdict(true, null);

        public static Verdict pass() {
            return PASS;
        }

        public static Verdict reject(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** Une route écartée, avec la porte qui l'a écartée et pourquoi. */
    public record Rejection(GatewayType gateway, String gate, String reason) {

        public String describe() {
            return gateway.getDisplayName() + " — " + reason + " [" + gate + "]";
        }
    }

    /** Sortie de la phase 1. */
    public record Result(List<Candidate> eligible, List<Rejection> rejected) {

        public boolean isEmpty() {
            return eligible.isEmpty();
        }

        /**
         * Résumé lisible des motifs de rejet, pour les messages d'erreur et les
         * journaux. Regroupe par motif pour éviter d'énumérer dix fois la même chose.
         */
        public String explain() {
            if (rejected.isEmpty()) {
                return "aucune route déclarée pour ce corridor";
            }
            Map<String, List<GatewayType>> byReason = rejected.stream()
                    .collect(Collectors.groupingBy(
                            Rejection::reason,
                            java.util.LinkedHashMap::new,
                            Collectors.mapping(Rejection::gateway, Collectors.toList())));

            return byReason.entrySet().stream()
                    .map(e -> e.getValue().stream()
                            .map(GatewayType::getDisplayName)
                            .collect(Collectors.joining(", ")) + " : " + e.getKey())
                    .collect(Collectors.joining(" · "));
        }
    }
}
