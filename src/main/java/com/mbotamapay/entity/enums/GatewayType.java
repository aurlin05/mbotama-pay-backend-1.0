package com.mbotamapay.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Enum des types de passerelles de paiement supportées
 */
@Getter
@RequiredArgsConstructor
public enum GatewayType {
    FEEXPAY("feexpay", "FeeXPay"),
    PAYTECH("paytech", "PayTech"),
    CINETPAY("cinetpay", "CinetPay");

    private final String code;
    private final String displayName;

    public static GatewayType fromCode(String code) {
        for (GatewayType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown gateway code: " + code);
    }
}
