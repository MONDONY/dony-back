package com.dony.api.automation.dto;

import java.util.Map;

public record UpdatePresetRequest(
        boolean enabled,
        Map<String, Object> config
) {}
