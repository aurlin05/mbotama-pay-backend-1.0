package com.mbotamapay.dto.transaction;

import com.mbotamapay.entity.enums.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Transaction response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private Long id;
    private String senderPhone;
    private String senderName;
    private String recipientPhone;
    private String recipientName;
    private Long amount;
    private Long fee;
    private Long totalAmount;
    private String currency;
    private String platform;
    private TransactionStatus status;
    private String description;
    private String externalReference;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
