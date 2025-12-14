package com.mbotamapay.dto.transfer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Réponse de transfert
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponseDto {

    private boolean success;
    private Long transactionId;
    private String reference;
    private Long amount;
    private Long fee;
    private Long totalAmount;
    private Integer feePercent;
    private String status;
    private String message;
    private String gateway;
    private String sourceCountry;
    private String destCountry;
    private String sourceOperatorName;
    private String destOperatorName;
}
