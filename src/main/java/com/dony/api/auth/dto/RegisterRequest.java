package com.dony.api.auth.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RegisterRequest(

    @Nullable
    @Pattern(
        regexp = "\\+[1-9]\\d{6,14}",
        message = "Le numéro doit être au format E.164 (ex: +33612345678)"
    )
    String phoneNumber,

    @Nullable
    @Email(message = "Format email invalide")
    String email,

    @NotEmpty(message = "Au moins un rôle est requis")
    @Size(max = 2, message = "Maximum 2 rôles")
    Set<String> roles
) {}
