package com.mbotamapay.gateway;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;

import java.util.EnumSet;
import java.util.Set;

/**
 * Déclaration par une passerelle de ce qu'elle sait réellement faire.
 *
 * <p>
 * C'est la source de vérité consultée par la porte d'éligibilité du moteur de
 * routage. Elle est volontairement portée par la passerelle elle-même : ajouter
 * un partenaire ne doit pas obliger à modifier le moteur.
 *
 * <p>
 * Trois déclarations coexistent dans le système et peuvent se contredire :
 * cette classe, la table {@code gateway_routes}, et
 * {@link MobileOperator#getSupportedGateways()}. Le
 * {@code CapabilityConsistencyValidator} les réconcilie au démarrage et signale
 * toute divergence.
 *
 * @param gateway            la passerelle décrite
 * @param collectionCountries pays où la passerelle sait encaisser (payin)
 * @param payoutCountries    pays où la passerelle sait verser (payout)
 * @param currencies         devises ISO manipulées (ex. XOF, XAF)
 * @param operators          opérateurs mobile money joignables en payout
 * @param supportsPayout     false pour une passerelle payin seulement
 */
public record GatewayCapabilities(
        GatewayType gateway,
        Set<Country> collectionCountries,
        Set<Country> payoutCountries,
        Set<String> currencies,
        Set<MobileOperator> operators,
        boolean supportsPayout) {

    public GatewayCapabilities {
        collectionCountries = immutable(collectionCountries, Country.class);
        payoutCountries = immutable(payoutCountries, Country.class);
        operators = immutable(operators, MobileOperator.class);
        currencies = currencies == null ? Set.of() : Set.copyOf(currencies);
    }

    private static <E extends Enum<E>> Set<E> immutable(Set<E> source, Class<E> type) {
        return source == null || source.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(source));
    }

    public boolean canPayoutTo(Country country) {
        return supportsPayout && payoutCountries.contains(country);
    }

    public boolean canCollectFrom(Country country) {
        return collectionCountries.contains(country);
    }

    public boolean handlesCurrency(String currency) {
        return currency != null && currencies.contains(currency);
    }

    /**
     * Un opérateur non déclaré n'est pas joignable. L'absence de déclaration vaut
     * refus : c'est le sens même d'une porte d'éligibilité.
     */
    public boolean canReach(MobileOperator operator) {
        return operator != null && operators.contains(operator);
    }
}
