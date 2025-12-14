package com.mbotamapay.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * KYC Verification Levels
 * Each level has different transaction limits
 */
@Getter
@RequiredArgsConstructor
public enum KycLevel {

    NONE(0L, "Non vérifié", "Complétez votre KYC pour envoyer de l'argent"),
    LEVEL_1(500_000L, "Vérifié Niveau 1", "Limite de 500 000 FCFA/mois"),
    LEVEL_2(Long.MAX_VALUE, "Vérifié Niveau 2", "Transactions illimitées");

    private final long transactionLimit;
    private final String displayName;
    private final String description;

    public boolean canTransact(long amount) {
        return amount <= transactionLimit;
    }
}
