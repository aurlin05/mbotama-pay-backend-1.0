package com.mbotamapay.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payment status response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentStatusResponse {

    private boolean success;
    private String status; // PENDING, COMPLETED, FAILED, CANCELLED
    private String message;
    private String externalReference;
    private Long amount;
    private String currency;
    private String paidAt;
}
