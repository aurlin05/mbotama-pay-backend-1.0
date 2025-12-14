package com.mbotamapay.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Enum des pays supportés pour les transactions
 * Contient le code ISO, le préfixe téléphonique et la devise
 */
@Getter
@RequiredArgsConstructor
public enum Country {
    BENIN("BJ", "229", "XOF", "Bénin"),
    BURKINA_FASO("BF", "226", "XOF", "Burkina Faso"),
    CAMEROON("CM", "237", "XAF", "Cameroun"),
    CONGO_BRAZZAVILLE("CG", "242", "XAF", "Congo-Brazzaville"),
    COTE_DIVOIRE("CI", "225", "XOF", "Côte d'Ivoire"),
    DRC("CD", "243", "CDF", "RD Congo"),
    GUINEA("GN", "224", "GNF", "Guinée"),
    MALI("ML", "223", "XOF", "Mali"),
    NIGER("NE", "227", "XOF", "Niger"),
    NIGERIA("NG", "234", "NGN", "Nigeria"),
    SENEGAL("SN", "221", "XOF", "Sénégal"),
    TOGO("TG", "228", "XOF", "Togo");

    private final String isoCode;
    private final String phonePrefix;
    private final String currency;
    private final String displayName;

    /**
     * Détecte le pays à partir d'un numéro de téléphone
     * Supporte les formats: +221... ou 221... ou 00221...
     */
    public static Optional<Country> fromPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }

        // Nettoyer le numéro (enlever espaces, tirets, +, 00)
        String cleaned = phoneNumber.replaceAll("[\\s\\-+]", "");
        if (cleaned.startsWith("00")) {
            cleaned = cleaned.substring(2);
        }

        final String finalCleaned = cleaned;
        return Arrays.stream(values())
                .filter(country -> finalCleaned.startsWith(country.phonePrefix))
                .findFirst();
    }

    /**
     * Trouve un pays par son code ISO
     */
    public static Optional<Country> fromIsoCode(String isoCode) {
        return Arrays.stream(values())
                .filter(country -> country.isoCode.equalsIgnoreCase(isoCode))
                .findFirst();
    }
}
