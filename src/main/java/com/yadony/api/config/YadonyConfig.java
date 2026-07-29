package com.yadony.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(YadonyConfigProperties.class)
public class YadonyConfig {
}
