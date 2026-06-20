package com.dony.api.messaging.dto;

public record ParticipantDTO(
        String id,
        String name,
        String avatarUrl,
        /** Téléphone révélé uniquement quand le deal est actif (sinon null). */
        String phone,
        /** Rôle relatif à la conversation : "Voyageur" | "Expéditeur". */
        String role,
        boolean kycVerified
) {}
