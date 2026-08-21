package com.mbotamapay.routing;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.MobileOperator;
import lombok.Builder;

/**
 * Tout ce dont le moteur a besoin pour décider, et rien de plus.
 *
 * <p>
 * Le montant est exprimé en unités mineures de la devise <em>source</em>. La
 * conversion vers la devise de destination est portée par la route retenue, pas
 * par l'appelant.
 */
@Builder
public record RoutingContext(
        Country sourceCountry,
        Country destCountry,
        MobileOperator sourceOperator,
        MobileOperator destOperator,
        long amount,
        String senderPhone,
        String recipientPhone) {

    public String sourceCurrency() {
        return sourceCountry.getCurrency();
    }

    public String destCurrency() {
        return destCountry.getCurrency();
    }

    public boolean isCrossCurrency() {
        return !sourceCurrency().equals(destCurrency());
    }

    public String corridor() {
        return sourceCountry.getIsoCode() + "-" + destCountry.getIsoCode();
    }
}
