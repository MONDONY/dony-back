package com.yadony.api.smsotp.dto;

import java.time.Instant;

public record SmsOtpSendResponse(Instant expiresAt) {}
