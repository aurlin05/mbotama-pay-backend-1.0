package com.mbotamapay.dto.transaction;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Transaction request DTO
 */
@Data
public class TransactionRequest {

    @NotBlank(message = "Le numéro du destinataire est requis")
    private String recipientPhone;

    private String recipientName;

    @NotNull(message = "Le montant est requis")
    @Min(value = 100, message = "Le montant minimum est de 100 XOF")
    private Long amount;

    @NotBlank(message = "La plateforme est requise")
    private String platform;

    private String description;
}
