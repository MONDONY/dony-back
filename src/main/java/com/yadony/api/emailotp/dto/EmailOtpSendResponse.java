package com.yadony.api.emailotp.dto;

import java.time.Instant;

public record EmailOtpSendResponse(Instant expiresAt) {}
