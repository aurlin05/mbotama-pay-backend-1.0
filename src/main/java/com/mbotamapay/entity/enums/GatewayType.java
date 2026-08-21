package com.mbotamapay.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * Enum des types de passerelles de paiement supportées
 */
@Getter
@RequiredArgsConstructor
public enum GatewayType {
    FEEXPAY("feexpay", "FeeXPay"),
    PAYTECH("paytech", "PayTech"),
    CINETPAY("cinetpay", "CinetPay"),
    PAYDUNYA("paydunya", "PayDunya"),
    MONETBIL("monetbil", "Monetbil");

    private final String code;
    private final String displayName;

    public static GatewayType fromCode(String code) {
        return find(code).orElseThrow(
                () -> new IllegalArgumentException("Unknown gateway code: " + code));
    }

    /**
     * Variante non levante : utile sur les chemins où un code inconnu ne doit pas
     * faire échouer la requête (données historiques, payloads partenaires).
     */
    public static Optional<GatewayType> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (GatewayType type : values()) {
            if (type.code.equalsIgnoreCase(code) || type.name().equalsIgnoreCase(code)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
