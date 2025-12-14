package com.mbotamapay.dto.routing;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Décision de routage pour une transaction
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingDecision {

    /**
     * Pays source de la transaction
     */
    private Country sourceCountry;

    /**
     * Pays destinataire
     */
    private Country destCountry;

    /**
     * Passerelle utilisée pour la collecte (Payin)
     */
    private GatewayType collectionGateway;

    /**
     * Passerelle utilisée pour le payout
     */
    private GatewayType payoutGateway;

    /**
     * True si le stock est utilisé (cross-gateway)
     */
    private boolean useStock;

    /**
     * Détail des frais calculés
     */
    private FeeBreakdown fees;

    /**
     * Raison du choix de routage
     */
    private String routingReason;

    /**
     * True si une route a été trouvée
     */
    private boolean routeFound;

    /**
     * Vérifie si c'est un transfert local (même pays)
     */
    public boolean isLocalTransfer() {
        return sourceCountry == destCountry;
    }

    /**
     * Vérifie si la même passerelle est utilisée pour collecte et payout
     */
    public boolean isSameGateway() {
        return collectionGateway == payoutGateway;
    }
}
