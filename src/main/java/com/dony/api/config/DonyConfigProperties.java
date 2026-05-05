package com.dony.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dony")
public record DonyConfigProperties(
    Commission commission
) {
    public record Commission(double rate) {}
}
