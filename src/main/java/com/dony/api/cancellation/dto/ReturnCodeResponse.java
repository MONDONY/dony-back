package com.dony.api.cancellation.dto;

import java.time.LocalDateTime;

/**
 * État du retour d'un colis annulé après remise. {@code returnCode} n'est renseigné
 * que pour l'expéditeur (qui le communique au voyageur) ; null après confirmation.
 */
public record ReturnCodeResponse(
        String returnCode,
        LocalDateTime returnDeadline,
        LocalDateTime returnedAt
) {}
