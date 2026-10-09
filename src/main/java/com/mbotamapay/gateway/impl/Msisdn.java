package com.mbotamapay.gateway.impl;

import com.mbotamapay.entity.enums.Country;

/**
 * Numéro au format international attendu par la plupart des passerelles :
 * chiffres seuls, indicatif pays inclus, sans « + » ni « 00 ».
 *
 * <p>
 * Le zéro initial du numéro local est conservé : il fait partie du numéro en
 * Côte d'Ivoire, au Bénin (numérotation à 10 chiffres) et au Congo.
 */
final class Msisdn {

    private Msisdn() {
    }

    static String of(String phone, Country country) {
        String cleaned = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("00")) {
            cleaned = cleaned.substring(2);
        }
        if (country != null && !cleaned.startsWith(country.getPhonePrefix())) {
            cleaned = country.getPhonePrefix() + cleaned;
        }
        return cleaned;
    }
}
