package com.mbotamapay.entity.enums;

/**
 * Transaction status
 */
public enum TransactionStatus {
    PENDING, // En attente
    PROCESSING, // En cours de traitement
    COMPLETED, // Terminée
    FAILED, // Échouée
    CANCELLED, // Annulée
    REFUNDED, // Remboursée
    EXPIRED // Expirée (transaction abandonnée)
}
