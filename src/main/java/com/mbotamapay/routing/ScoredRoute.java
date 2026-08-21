package com.mbotamapay.routing;

import com.mbotamapay.dto.FeeBreakdown;
import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.GatewayType;
import lombok.Builder;

import java.util.Comparator;

/**
 * Une route éligible, chiffrée et notée.
 *
 * <p>
 * L'ordre naturel est « meilleure d'abord ». Il est <strong>totalement
 * déterministe</strong> : à score égal l'ancien moteur laissait l'ordre dépendre
 * de celui renvoyé par la base, si bien que deux prévisualisations identiques
 * pouvaient coter des prix différents.
 *
 * @param sourceAmount   montant envoyé, en unités mineures de {@code sourceCurrency}
 * @param payoutAmount   montant reçu, en unités mineures de {@code payoutCurrency}
 * @param fees           frais prélevés à l'expéditeur, en devise source
 * @param observations   nombre d'appels observés sur la fenêtre — sert à savoir
 *                       si le score de fiabilité repose sur des preuves
 */
@Builder
public record ScoredRoute(
        GatewayRoute route,
        GatewayType gateway,
        int totalScore,
        int costScore,
        int marginScore,
        int reliabilityScore,
        int latencyScore,
        FeeBreakdown fees,
        long sourceAmount,
        String sourceCurrency,
        long payoutAmount,
        String payoutCurrency,
        long observations) implements Comparable<ScoredRoute> {

    private static final Comparator<ScoredRoute> ORDER = Comparator
            .comparingInt(ScoredRoute::totalScore).reversed()
            // départage 1 : le moins cher pour le client
            .thenComparingLong(r -> r.fees().getTotalFee())
            // départage 2 : la priorité déclarée en configuration
            .thenComparingInt(r -> r.route().getPriority() == null
                    ? Integer.MAX_VALUE
                    : r.route().getPriority())
            // départage 3 : stable, pour que deux appels identiques rendent le même ordre
            .thenComparing(r -> r.gateway().name());

    @Override
    public int compareTo(ScoredRoute other) {
        return ORDER.compare(this, other);
    }

    /** Total débité à l'expéditeur, en devise source. */
    public long totalCharged() {
        return sourceAmount + fees.getTotalFee();
    }

    /** Marge nette de la plateforme, signée. Négative = la plateforme paie. */
    public long netMargin() {
        return fees.getNetMargin() == null ? 0L : fees.getNetMargin();
    }

    public boolean isCrossCurrency() {
        return !sourceCurrency.equals(payoutCurrency);
    }
}
