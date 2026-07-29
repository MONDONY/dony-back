package com.yadony.api.messaging.dto;

public record ImageUploadResponse(String presignedUrl, String s3Key) {}
