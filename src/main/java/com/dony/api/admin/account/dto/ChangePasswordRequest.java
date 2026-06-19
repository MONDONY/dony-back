package com.dony.api.admin.account.dto;

/**
 * Request DTO for changing an admin's own password.
 *
 * Task 9 — AdminAccountController
 */
public record ChangePasswordRequest(String newPassword) {}
