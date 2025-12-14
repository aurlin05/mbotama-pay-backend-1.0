package com.mbotamapay.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Request DTO for phone number registration/login
 */
@Data
public class PhoneAuthRequest {

    @NotBlank(message = "Le numéro de téléphone est requis")
    @Pattern(regexp = "^[0-9]{8,15}$", message = "Format de numéro invalide")
    private String phoneNumber;

    @NotBlank(message = "L'indicatif pays est requis")
    @Pattern(regexp = "^\\+[0-9]{1,4}$", message = "Format d'indicatif invalide")
    private String countryCode = "+221";

    // Optional profile fields for registration
    private String firstName;
    private String lastName;

    @Email(message = "Format d'email invalide")
    private String email;
}
