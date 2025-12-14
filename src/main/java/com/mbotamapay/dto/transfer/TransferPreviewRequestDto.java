package com.mbotamapay.dto.transfer;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Requête de preview de transfert (avant exécution)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferPreviewRequestDto {

    @NotBlank(message = "Le numéro de l'expéditeur est requis")
    private String senderPhone;

    /**
     * Code de l'opérateur source (ex: ORANGE_SN)
     */
    @NotBlank(message = "L'opérateur source est requis")
    private String sourceOperator;

    @NotBlank(message = "Le numéro du destinataire est requis")
    private String recipientPhone;

    /**
     * Code de l'opérateur destination (ex: MTN_BJ)
     */
    @NotBlank(message = "L'opérateur destination est requis")
    private String destOperator;

    @NotNull(message = "Le montant est requis")
    @Min(value = 100, message = "Le montant minimum est de 100 XOF")
    private Long amount;
}
