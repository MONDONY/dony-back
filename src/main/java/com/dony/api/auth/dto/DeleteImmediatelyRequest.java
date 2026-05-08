package com.dony.api.auth.dto;

import jakarta.validation.constraints.AssertTrue;

public record DeleteImmediatelyRequest(
        @AssertTrue(message = "Vous devez confirmer la suppression")
        boolean confirmationAcknowledged
) {}
