package com.mbotamapay.gateway;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Redéfinition en configuration de la couverture déclarée par une passerelle.
 *
 * <p>
 * La couverture d'un agrégateur n'est pas une propriété du code : elle change
 * quand le partenaire ouvre un marché, et elle a changé au moins une fois sans
 * que le code suive — le javadoc de FeexPay annonçait six pays quand la
 * constante n'en déclarait que quatre, et une migration a dû supprimer à la main
 * des routes devenues fausses.
 *
 * <p>
 * Les valeurs codées dans chaque passerelle ne sont donc plus qu'un défaut. Une
 * mise à jour de couverture se fait par variable d'environnement, sans
 * redéploiement, et le contrôle de cohérence au démarrage vérifie immédiatement
 * que la table de routes suit.
 *
 * <pre>
 * gateway:
 *   capabilities:
 *     feexpay:
 *       payout-countries: BENIN,TOGO,COTE_DIVOIRE,CONGO_BRAZZAVILLE,SENEGAL
 *       currencies: XOF,XAF
 *       operators: MTN_BJ,MOOV_BJ,ORANGE_SN,WAVE_SN
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "gateway")
@Data
@Slf4j
public class GatewayCapabilityOverrides {

    /** Indexé par code de passerelle en minuscules ({@code feexpay}, {@code monetbil}…). */
    private Map<String, Declaration> capabilities = new LinkedHashMap<>();

    /**
     * Applique la redéfinition si elle existe, sinon rend le défaut du code.
     * Chaque champ est indépendant : ne redéfinir que les pays de payout laisse
     * les opérateurs et les devises inchangés.
     */
    public GatewayCapabilities resolve(GatewayType gateway, GatewayCapabilities fallback) {
        Declaration declaration = capabilities.get(gateway.getCode().toLowerCase());
        if (declaration == null || declaration.isEmpty()) {
            return fallback;
        }

        GatewayCapabilities resolved = new GatewayCapabilities(
                gateway,
                countries(declaration.getCollectionCountries(), fallback.collectionCountries()),
                countries(declaration.getPayoutCountries(), fallback.payoutCountries()),
                strings(declaration.getCurrencies(), fallback.currencies()),
                operators(declaration.getOperators(), fallback.operators()),
                declaration.getSupportsPayout() == null
                        ? fallback.supportsPayout()
                        : declaration.getSupportsPayout());

        log.info("Gateway {} capabilities overridden by configuration: payout={}, currencies={}, operators={}",
                gateway, resolved.payoutCountries(), resolved.currencies(), resolved.operators().size());
        return resolved;
    }

    private Set<Country> countries(List<String> declared, Set<Country> fallback) {
        if (declared == null || declared.isEmpty()) {
            return fallback;
        }
        Set<Country> parsed = EnumSet.noneOf(Country.class);
        for (String name : declared) {
            parse(name, Country.class).ifPresentOrElse(parsed::add,
                    () -> log.error("Unknown country in gateway capability override: '{}'", name));
        }
        return parsed;
    }

    private Set<MobileOperator> operators(List<String> declared, Set<MobileOperator> fallback) {
        if (declared == null || declared.isEmpty()) {
            return fallback;
        }
        Set<MobileOperator> parsed = EnumSet.noneOf(MobileOperator.class);
        for (String name : declared) {
            parse(name, MobileOperator.class).ifPresentOrElse(parsed::add,
                    () -> log.error("Unknown operator in gateway capability override: '{}'", name));
        }
        return parsed;
    }

    private Set<String> strings(List<String> declared, Set<String> fallback) {
        return declared == null || declared.isEmpty()
                ? fallback
                : Set.copyOf(declared.stream().map(s -> s.trim().toUpperCase()).toList());
    }

    private <E extends Enum<E>> Optional<E> parse(String name, Class<E> type) {
        try {
            return Optional.of(Enum.valueOf(type, name.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Data
    public static class Declaration {
        private List<String> collectionCountries;
        private List<String> payoutCountries;
        private List<String> currencies;
        private List<String> operators;
        private Boolean supportsPayout;

        boolean isEmpty() {
            return isBlank(collectionCountries) && isBlank(payoutCountries)
                    && isBlank(currencies) && isBlank(operators) && supportsPayout == null;
        }

        private boolean isBlank(List<String> list) {
            return list == null || list.isEmpty();
        }
    }
}
