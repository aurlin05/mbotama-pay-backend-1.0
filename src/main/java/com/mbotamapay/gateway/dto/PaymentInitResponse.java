package com.mbotamapay.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payment initialization response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitResponse {

    private boolean success;
    private String message;
    private String paymentUrl;
    private String externalReference;
    private String transactionId;
}
