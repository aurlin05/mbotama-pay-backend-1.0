package com.mbotamapay.routing.gates;

import com.mbotamapay.entity.GatewayStock;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.routing.Eligibility.Candidate;
import com.mbotamapay.routing.Eligibility.Verdict;
import com.mbotamapay.routing.RoutingContext;

import java.util.Map;

/**
 * Une porte d'éligibilité : un prédicat nommé qui répond par oui ou par non, et
 * motive son refus.
 *
 * <p>
 * Les portes ne pondèrent rien. C'est toute la différence avec le score : un
 * critère qui rend une route inexécutable n'a pas à être « pris en compte », il
 * doit éliminer. Auparavant « cette passerelle ne verse pas dans ce pays »
 * coûtait dix points sur cent, et une route impossible restait élue si elle
 * était bon marché.
 *
 * <p>
 * L'ordre d'exécution suit {@code @Order} : les portes sans entrée/sortie
 * d'abord, celles qui lisent la base ensuite.
 */
public interface EligibilityGate {

    /** Nom court, repris tel quel dans le motif de rejet et la décision. */
    String name();

    Verdict test(Candidate candidate, GateContext context);

    /**
     * Contexte d'évaluation : la requête, plus les données préchargées en une
     * seule fois pour que les portes n'aient pas à interroger la base
     * individuellement.
     *
     * @param request    la demande de routage
     * @param destStocks soldes disponibles dans le pays de destination, chargés en
     *                   une requête pour l'ensemble des candidats
     */
    record GateContext(RoutingContext request, Map<GatewayType, GatewayStock> destStocks) {
    }
}
