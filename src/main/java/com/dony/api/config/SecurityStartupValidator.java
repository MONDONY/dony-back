package com.dony.api.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Validates security-critical configuration at startup.
 * Fails fast in production if weak or missing secrets are detected.
 */
@Configuration
public class SecurityStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);

    private static final String KNOWN_WEAK_SECRET = "local-dev-secret-change-me";
    private static final int MIN_SECRET_LENGTH = 16;

    @Value("${dony.internal.secret:}")
    private String internalSecret;

    private final Environment environment;

    public SecurityStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        if (isProd) {
            // Hard fail in production if the secret is weak or default
            if (internalSecret == null || internalSecret.isBlank()) {
                throw new IllegalStateException(
                    "[SECURITY] INTERNAL_SHARED_SECRET must be set in production. " +
                    "Generate with: openssl rand -hex 32");
            }
            if (KNOWN_WEAK_SECRET.equals(internalSecret)) {
                throw new IllegalStateException(
                    "[SECURITY] INTERNAL_SHARED_SECRET is set to the default dev value. " +
                    "This is not allowed in production. Generate with: openssl rand -hex 32");
            }
            if (internalSecret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException(
                    "[SECURITY] INTERNAL_SHARED_SECRET is too short (minimum " +
                    MIN_SECRET_LENGTH + " characters required in production).");
            }
        } else {
            // Warn in non-production environments
            if (KNOWN_WEAK_SECRET.equals(internalSecret)) {
                log.warn("[SECURITY] INTERNAL_SHARED_SECRET uses the default dev value '{}'. " +
                    "Set INTERNAL_SHARED_SECRET env var before deploying to production.",
                    KNOWN_WEAK_SECRET);
            }
        }
    }
}
