package com.mbotamapay.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Table des taux de conversion entre devises de corridor.
 *
 * <p>
 * Le moteur manipulait auparavant {@code "XOF"} en dur sur tous les chemins,
 * alors que les pays desservis couvrent quatre devises : XOF, XAF, CDF et GNF.
 * Un versement en Guinée partait donc étiqueté XOF pour un montant exprimé en
 * GNF, dont l'ordre de grandeur est quatorze fois différent.
 *
 * <p>
 * Règle retenue : <strong>pas de taux, pas de corridor</strong>. Un corridor
 * dont les devises diffèrent et pour lequel aucun taux n'est déclaré est
 * inéligible, avec motif. C'est plus restrictif qu'avant, et c'est voulu :
 * mieux vaut un corridor fermé qu'un corridor qui verse le mauvais montant.
 *
 * <p>
 * XOF et XAF sont déclarés à parité (1,000000). Les deux monnaies sont arrimées
 * à l'euro au même taux fixe (655,957), elles sont donc numériquement
 * équivalentes — mais elles restent deux devises distinctes, émises par deux
 * banques centrales, et l'étiquette envoyée au partenaire doit être la bonne.
 */
@Component
@ConfigurationProperties(prefix = "routing.fx")
@Slf4j
public class FxRegistry {

    /**
     * Taux indexés par paire {@code SOURCE-DEST}, ex. {@code XOF-XAF: 1.0}.
     * Alimenté depuis la configuration.
     */
    private Map<String, BigDecimal> rates = new LinkedHashMap<>();

    public Map<String, BigDecimal> getRates() {
        return rates;
    }

    public void setRates(Map<String, BigDecimal> rates) {
        this.rates = rates == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rates);
    }

    /**
     * Taux applicable pour convertir {@code from} vers {@code to}.
     *
     * @return vide si la conversion n'est pas déclarée — le corridor doit alors
     *         être refusé, jamais approximé
     */
    public Optional<BigDecimal> rate(String from, String to) {
        if (from == null || to == null) {
            return Optional.empty();
        }
        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE);
        }
        BigDecimal direct = rates.get(key(from, to));
        if (direct != null && direct.signum() > 0) {
            return Optional.of(direct);
        }
        // Un taux inverse déclaré vaut déclaration : XOF-XAF: 1.0 couvre XAF-XOF.
        BigDecimal inverse = rates.get(key(to, from));
        if (inverse != null && inverse.signum() > 0) {
            return Optional.of(BigDecimal.ONE.divide(inverse, 10, RoundingMode.HALF_EVEN));
        }
        return Optional.empty();
    }

    public boolean isConvertible(String from, String to) {
        return rate(from, to).isPresent();
    }

    /**
     * Convertit un montant en unités mineures. Arrondi au plus proche : sur un
     * versement, l'écart d'une unité ne doit favoriser systématiquement ni le
     * client ni la plateforme.
     */
    public Optional<Long> convert(long amount, String from, String to) {
        return rate(from, to)
                .map(r -> BigDecimal.valueOf(amount)
                        .multiply(r)
                        .setScale(0, RoundingMode.HALF_EVEN)
                        .longValueExact());
    }

    private String key(String from, String to) {
        return from.toUpperCase() + "-" + to.toUpperCase();
    }
}
