package com.mbotamapay.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payment initialization request DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitRequest {

    private String transactionReference;
    private Long amount;
    private String currency;
    private String senderPhone;
    private String senderName;
    private String senderEmail;
    private String recipientPhone;
    private String recipientName;
    private String description;
    private String callbackUrl;
    private String returnUrl;
    private String cancelUrl;
}
