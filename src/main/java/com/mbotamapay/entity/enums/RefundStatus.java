package com.mbotamapay.entity.enums;

/**
 * Refund Status
 */
public enum RefundStatus {
    PENDING, // Demande de remboursement initiée
    PROCESSING, // En cours de traitement par la gateway
    COMPLETED, // Remboursement effectué
    FAILED, // Remboursement échoué
    REJECTED // Remboursement refusé (conditions non remplies)
}
