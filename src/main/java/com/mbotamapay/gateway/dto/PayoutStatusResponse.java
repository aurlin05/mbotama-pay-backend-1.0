package com.mbotamapay.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Réponse de statut de payout
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutStatusResponse {

    private boolean success;
    private String status;
    private String message;
    private String externalReference;
    private Long amount;
    private String currency;
    private String processedAt;
}
