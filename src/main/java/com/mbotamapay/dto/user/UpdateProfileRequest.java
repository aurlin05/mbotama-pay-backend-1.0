package com.mbotamapay.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;

/**
 * User profile update request DTO
 */
@Data
public class UpdateProfileRequest {

    @Size(min = 2, max = 100, message = "Le prénom doit contenir entre 2 et 100 caractères")
    private String firstName;

    @Size(min = 2, max = 100, message = "Le nom doit contenir entre 2 et 100 caractères")
    private String lastName;

    @Email(message = "Email invalide")
    private String email;

    @Size(min = 2, max = 255, message = "L'adresse doit contenir entre 2 et 255 caractères")
    private String address;

    @Size(min = 2, max = 120, message = "La ville doit contenir entre 2 et 120 caractères")
    private String city;

    private LocalDate dateOfBirth;
}
