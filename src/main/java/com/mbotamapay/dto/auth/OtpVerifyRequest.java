package com.mbotamapay.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for OTP verification
 */
@Data
public class OtpVerifyRequest {

    @NotBlank(message = "Le numéro de téléphone est requis")
    private String phoneNumber;

    @NotBlank(message = "Le code OTP est requis")
    @Size(min = 6, max = 6, message = "Le code doit contenir 6 chiffres")
    @Pattern(regexp = "^[0-9]{6}$", message = "Le code doit contenir uniquement des chiffres")
    private String code;
}
