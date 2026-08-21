package com.mbotamapay.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Enum des opérateurs mobile money supportés
 * Chaque opérateur est lié à un pays et aux passerelles qui le supportent
 */
@Getter
@RequiredArgsConstructor
public enum MobileOperator {
    // Bénin — préfixes d'après la table opérateurs Monetbil (Payment API v1)
    MTN_BJ("MTN Bénin", Country.BENIN,
            Set.of("50", "51", "52", "53", "54", "56", "57", "59", "61", "62",
                    "66", "67", "69", "96", "97"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA,
                    GatewayType.MONETBIL)),
    MOOV_BJ("Moov Bénin", Country.BENIN,
            Set.of("55", "58", "60", "63", "64", "65", "68", "87", "89",
                    "92", "93", "94", "95", "98", "99"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA,
                    GatewayType.MONETBIL)),
    // 90 et 91 sont attribués à MTN dans la table Monetbil et à Celtiis ici.
    // Divergence non tranchée : les séries sont laissées à Celtiis, qui est le
    // seul des trois à être servi par une passerelle les déclarant.
    CELTIIS_BJ("Celtiis Bénin", Country.BENIN, Set.of("90", "91"),
            EnumSet.of(GatewayType.FEEXPAY)),

    // Sénégal — Wave est un portefeuille superposé, pas un réseau : son rattachement
    // au préfixe 78 est conservé en l'état, le modifier réacheminerait du trafic réel.
    ORANGE_SN("Orange Sénégal", Country.SENEGAL, Set.of("77"),
            EnumSet.of(GatewayType.PAYTECH, GatewayType.CINETPAY, GatewayType.PAYDUNYA,
                    GatewayType.MONETBIL)),
    FREE_SN("Free Sénégal", Country.SENEGAL, Set.of("76"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    WAVE_SN("Wave Sénégal", Country.SENEGAL, Set.of("78"),
            EnumSet.of(GatewayType.PAYTECH, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),

    // Côte d'Ivoire
    ORANGE_CI("Orange Côte d'Ivoire", Country.COTE_DIVOIRE, Set.of("07"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    MTN_CI("MTN Côte d'Ivoire", Country.COTE_DIVOIRE, Set.of("05"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    MOOV_CI("Moov Côte d'Ivoire", Country.COTE_DIVOIRE, Set.of("01"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    WAVE_CI("Wave Côte d'Ivoire", Country.COTE_DIVOIRE, Set.of("02"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),

    // Togo
    TOGOCOM_TG("Togocom", Country.TOGO, Set.of("90", "91", "92", "93"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    MOOV_TG("Moov Togo", Country.TOGO, Set.of("96", "97", "98", "99"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),

    // Mali
    ORANGE_ML("Orange Mali", Country.MALI, Set.of("7"),
            EnumSet.of(GatewayType.PAYTECH, GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    MOOV_ML("Moov Mali", Country.MALI, Set.of("6"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.PAYDUNYA)),

    // Burkina Faso
    ORANGE_BF("Orange Burkina", Country.BURKINA_FASO, Set.of("07"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    MOOV_BF("Moov Burkina", Country.BURKINA_FASO, Set.of("06"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.PAYDUNYA)),

    // Congo-Brazzaville — Monetbil ouvre un second chemin sur ce marché, qui ne
    // dépendait que d'une seule passerelle.
    MTN_CG("MTN Congo", Country.CONGO_BRAZZAVILLE, Set.of("06"),
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.MONETBIL)),
    AIRTEL_CG("Airtel Congo", Country.CONGO_BRAZZAVILLE, Set.of("04", "05"),
            EnumSet.of(GatewayType.MONETBIL)),

    // Cameroun — les séries 65x et 68x n'étaient rattachées à aucun opérateur,
    // alors qu'elles représentent l'essentiel du parc.
    ORANGE_CM("Orange Cameroun", Country.CAMEROON,
            Set.of("69", "655", "656", "657", "658", "659",
                    "685", "686", "687", "688", "689"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.MONETBIL)),
    MTN_CM("MTN Cameroun", Country.CAMEROON,
            Set.of("67", "650", "651", "652", "653", "654",
                    "680", "681", "682", "683", "684"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.MONETBIL)),

    // Guinée
    ORANGE_GN("Orange Guinée", Country.GUINEA, Set.of("62", "610", "611", "612"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.MONETBIL)),
    MTN_GN("MTN Guinée", Country.GUINEA, Set.of("66"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.MONETBIL)),

    // Niger
    AIRTEL_NE("Airtel Niger", Country.NIGER, Set.of("97"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.PAYDUNYA)),
    MOOV_NE("Moov Niger", Country.NIGER, Set.of("90"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.PAYDUNYA)),

    // RD Congo
    ORANGE_CD("Orange RDC", Country.DRC, Set.of("80", "84", "85", "89"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.MONETBIL)),
    VODACOM_CD("Vodacom RDC", Country.DRC, Set.of("81", "82", "83"),
            EnumSet.of(GatewayType.CINETPAY)),
    AIRTEL_CD("Airtel RDC", Country.DRC, Set.of("97", "98", "99"),
            EnumSet.of(GatewayType.CINETPAY, GatewayType.MONETBIL)),
    AFRICELL_CD("Africell RDC", Country.DRC, Set.of("90", "91"),
            EnumSet.of(GatewayType.MONETBIL));

    private final String displayName;
    private final Country country;

    /**
     * Préfixes locaux exploités par l'opérateur, après le code pays.
     *
     * <p>
     * Un seul préfixe était déclaré par opérateur, ce qui laissait des plages
     * entières non détectées — au Cameroun, les séries 65x et 68x n'étaient
     * rattachées à personne alors qu'elles représentent l'essentiel du parc.
     */
    private final Set<String> prefixes;

    private final Set<GatewayType> supportedGateways;

    /**
     * Premier préfixe déclaré, conservé pour l'affichage et la compatibilité.
     *
     * @deprecated préférer {@link #getPrefixes()} : un opérateur en exploite
     *             généralement plusieurs.
     */
    @Deprecated
    public String getPrefix() {
        return prefixes.iterator().next();
    }

    /**
     * Détecte l'opérateur à partir du numéro de téléphone (après le préfixe pays).
     *
     * <p>
     * La correspondance se fait sur le <strong>préfixe le plus long d'abord</strong>.
     * Sans cela, un opérateur déclarant {@code "62"} capterait les numéros de
     * celui qui déclare {@code "610"}, selon le seul ordre de déclaration de
     * l'énumération.
     *
     * <p>
     * L'exhaustivité de ces préfixes est devenue critique : l'opérateur du
     * bénéficiaire est désormais une <em>porte d'éligibilité</em>, et non plus une
     * pondération. Un numéro dont l'opérateur n'est pas reconnu est refusé, là où
     * il était auparavant acheminé au jugé.
     */
    public static Optional<MobileOperator> fromPhoneNumber(String phoneNumber, Country country) {
        if (phoneNumber == null || country == null) {
            return Optional.empty();
        }

        // Extraire la partie locale du numéro (après le préfixe pays)
        String cleaned = phoneNumber.replaceAll("[\\s\\-+]", "");
        if (cleaned.startsWith("00")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.startsWith(country.getPhonePrefix())) {
            cleaned = cleaned.substring(country.getPhonePrefix().length());
        }

        final String localNumber = cleaned;
        return Arrays.stream(values())
                .filter(op -> op.country == country)
                .flatMap(op -> op.prefixes.stream().map(p -> java.util.Map.entry(p, op)))
                .filter(entry -> localNumber.startsWith(entry.getKey()))
                .max(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(java.util.Map.Entry::getValue);
    }

    /** Tous les préfixes déclarés, tous opérateurs confondus, pour un pays. */
    public static java.util.List<String> prefixesFor(Country country) {
        return Arrays.stream(values())
                .filter(op -> op.country == country)
                .flatMap(op -> op.prefixes.stream())
                .sorted()
                .toList();
    }

    /**
     * Vérifie si cet opérateur supporte une passerelle donnée
     */
    public boolean supportsGateway(GatewayType gateway) {
        return supportedGateways.contains(gateway);
    }

    /**
     * Trouve tous les opérateurs d'un pays
     */
    public static Set<MobileOperator> getOperatorsForCountry(Country country) {
        return Arrays.stream(values())
                .filter(op -> op.country == country)
                .collect(java.util.stream.Collectors.toSet());
    }
}
