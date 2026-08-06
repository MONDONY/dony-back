package com.yadony.api.smsotp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Rattachement d'un numéro au compte authentifié. Le code OTP est exigé dans la
 * même requête que le numéro : c'est ce qui prouve au serveur que l'appelant
 * possède bien ce téléphone. Sans cette preuve, n'importe quel porteur d'un
 * token Firebase valide pourrait s'attribuer le numéro d'un tiers.
 */
public record SmsOtpAttachRequest(
        @NotBlank
        @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "Le numéro doit être au format E.164 (ex: +33612345678)")
        String phoneNumber,

        @NotBlank @Pattern(regexp = "\\d{6}", message = "Code à 6 chiffres requis") String code
) {}
