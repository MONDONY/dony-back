package com.dony.api.emailotp.dto;

import java.time.Instant;

public record EmailOtpSendResponse(Instant expiresAt) {}
