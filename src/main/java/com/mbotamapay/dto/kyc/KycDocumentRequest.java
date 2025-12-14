package com.mbotamapay.dto.kyc;

import com.mbotamapay.entity.enums.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * KYC document upload request DTO
 */
@Data
public class KycDocumentRequest {

    @NotNull(message = "Le type de document est requis")
    private DocumentType documentType;

    @NotBlank(message = "L'URL du document est requise")
    private String documentUrl;
}
