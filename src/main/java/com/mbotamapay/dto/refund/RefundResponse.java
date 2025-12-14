package com.mbotamapay.dto.refund;

import com.mbotamapay.entity.enums.RefundStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Refund Response DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundResponse {

    private Long id;
    private Long transactionId;
    private String transactionReference;
    private Long amount;
    private String currency;
    private RefundStatus status;
    private String reason;
    private String externalReference;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}
