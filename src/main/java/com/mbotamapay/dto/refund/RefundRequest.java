package com.mbotamapay.dto.refund;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Refund Request DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

    @NotBlank(message = "La raison du remboursement est requise")
    @Size(min = 10, max = 500, message = "La raison doit contenir entre 10 et 500 caractères")
    private String reason;
}
