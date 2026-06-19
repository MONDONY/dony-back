package com.dony.api.admin.account.dto;

/**
 * Response DTO returning credentials for a newly created or password-reset admin account.
 * The temporaryPassword is displayed exactly once and never stored.
 */
public record CredentialsResponse(String login, String temporaryPassword) {}
