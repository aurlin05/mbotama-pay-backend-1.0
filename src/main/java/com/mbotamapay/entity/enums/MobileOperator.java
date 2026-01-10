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
    // Bénin
    MTN_BJ("MTN Bénin", Country.BENIN, "97", EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY)),
    MOOV_BJ("Moov Bénin", Country.BENIN, "95", EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY)),
    CELTIIS_BJ("Celtiis Bénin", Country.BENIN, "90", EnumSet.of(GatewayType.FEEXPAY)),

    // Sénégal
    ORANGE_SN("Orange Sénégal", Country.SENEGAL, "77",
            EnumSet.of(GatewayType.PAYTECH, GatewayType.CINETPAY)),
    FREE_SN("Free Sénégal", Country.SENEGAL, "76", EnumSet.of(GatewayType.CINETPAY)),
    WAVE_SN("Wave Sénégal", Country.SENEGAL, "78", EnumSet.of(GatewayType.PAYTECH, GatewayType.CINETPAY)),

    // Côte d'Ivoire
    ORANGE_CI("Orange Côte d'Ivoire", Country.COTE_DIVOIRE, "07",
            EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY)),
    MTN_CI("MTN Côte d'Ivoire", Country.COTE_DIVOIRE, "05", EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY)),
    MOOV_CI("Moov Côte d'Ivoire", Country.COTE_DIVOIRE, "01", EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY)),
    WAVE_CI("Wave Côte d'Ivoire", Country.COTE_DIVOIRE, "02", EnumSet.of(GatewayType.FEEXPAY, GatewayType.CINETPAY)),

    // Togo
    TOGOCOM_TG("Togocom", Country.TOGO, "90", EnumSet.of(GatewayType.FEEXPAY)),
    MOOV_TG("Moov Togo", Country.TOGO, "99", EnumSet.of(GatewayType.FEEXPAY)),

    // Mali
    ORANGE_ML("Orange Mali", Country.MALI, "7", EnumSet.of(GatewayType.PAYTECH, GatewayType.CINETPAY)),
    MOOV_ML("Moov Mali", Country.MALI, "6", EnumSet.of(GatewayType.CINETPAY)),

    // Burkina Faso
    ORANGE_BF("Orange Burkina", Country.BURKINA_FASO, "07", EnumSet.of(GatewayType.CINETPAY)),
    MOOV_BF("Moov Burkina", Country.BURKINA_FASO, "06", EnumSet.of(GatewayType.CINETPAY)),

    // Congo-Brazzaville
    MTN_CG("MTN Congo", Country.CONGO_BRAZZAVILLE, "06", EnumSet.of(GatewayType.FEEXPAY)),

    // Cameroun
    ORANGE_CM("Orange Cameroun", Country.CAMEROON, "69", EnumSet.of(GatewayType.CINETPAY)),
    MTN_CM("MTN Cameroun", Country.CAMEROON, "67", EnumSet.of(GatewayType.CINETPAY)),

    // Guinée
    ORANGE_GN("Orange Guinée", Country.GUINEA, "62", EnumSet.of(GatewayType.CINETPAY)),
    MTN_GN("MTN Guinée", Country.GUINEA, "66", EnumSet.of(GatewayType.CINETPAY)),

    // Niger
    AIRTEL_NE("Airtel Niger", Country.NIGER, "97", EnumSet.of(GatewayType.CINETPAY)),
    MOOV_NE("Moov Niger", Country.NIGER, "90", EnumSet.of(GatewayType.CINETPAY)),

    // RD Congo
    ORANGE_CD("Orange RDC", Country.DRC, "89", EnumSet.of(GatewayType.CINETPAY)),
    VODACOM_CD("Vodacom RDC", Country.DRC, "81", EnumSet.of(GatewayType.CINETPAY)),
    AIRTEL_CD("Airtel RDC", Country.DRC, "99", EnumSet.of(GatewayType.CINETPAY));

    private final String displayName;
    private final Country country;
    private final String prefix;
    private final Set<GatewayType> supportedGateways;

    /**
     * Détecte l'opérateur à partir du numéro de téléphone (après le préfixe pays)
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
                .filter(op -> localNumber.startsWith(op.prefix))
                .findFirst();
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
