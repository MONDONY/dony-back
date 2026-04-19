package com.dony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RegisterRequest(

    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Pattern(
        regexp = "\\+[1-9]\\d{6,14}",
        message = "Le numéro doit être au format E.164 (ex: +33612345678)"
    )
    String phoneNumber,

    @NotEmpty(message = "Au moins un rôle est requis")
    @Size(max = 2, message = "Maximum 2 rôles")
    Set<String> roles
) {}
