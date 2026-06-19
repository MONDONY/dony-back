package com.dony.api.admin.users;

public record AdminUserFilter(
        String status,
        String role,
        String kyc,
        Boolean pro,
        String city,
        String query
) {}
