package com.dony.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Set;

public record UpdateProfileRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Email @Size(max = 255) String email,
    @Past LocalDate birthDate,
    @Size(max = 100) String city,
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Format E.164 requis (ex: +33612345678)")
    String phoneNumber,
    @Size(max = 280) String bio,
    Set<String> languages,
    @Pattern(regexp = "AVION|VOITURE|TRAIN", message = "Mode de transport invalide")
    String transportMode
) {}
