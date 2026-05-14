package com.dony.api.requests;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RequestsConfig.class)
public class RequestsAutoConfig {
}
