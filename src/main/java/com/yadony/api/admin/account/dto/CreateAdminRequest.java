package com.yadony.api.admin.account.dto;

import com.yadony.api.admin.account.AdminRole;

public record CreateAdminRequest(String email, AdminRole role) {}
