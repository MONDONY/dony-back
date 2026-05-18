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

    @Value("${firebase.service-account-path:}")
    private String firebaseServiceAccountPath;

    private final Environment environment;

    public SecurityStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        if (isProd) {
            validateInternalSecret();
            validateFirebaseCredentials();
        } else {
            if (KNOWN_WEAK_SECRET.equals(internalSecret)) {
                log.warn("[SECURITY] INTERNAL_SHARED_SECRET uses the default dev value '{}'. " +
                    "Set INTERNAL_SHARED_SECRET env var before deploying to production.",
                    KNOWN_WEAK_SECRET);
            }
        }
    }

    private void validateInternalSecret() {
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
    }

    private void validateFirebaseCredentials() {
        // In production, the service account key must never be bundled inside the JAR.
        // It must be loaded via GOOGLE_APPLICATION_CREDENTIALS pointing to an external file.
        boolean hasClasspathCredentials = !firebaseServiceAccountPath.isBlank();
        String googleAppCreds = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        boolean hasEnvCredentials = googleAppCreds != null && !googleAppCreds.isBlank();

        if (hasClasspathCredentials && !hasEnvCredentials) {
            throw new IllegalStateException(
                "[SECURITY] firebase.service-account-path is configured in production. " +
                "Embedding Firebase credentials inside the JAR is not allowed. " +
                "Set GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json instead " +
                "and remove firebase.service-account-path from the production config.");
        }
        if (!hasEnvCredentials) {
            throw new IllegalStateException(
                "[SECURITY] GOOGLE_APPLICATION_CREDENTIALS is not set. " +
                "Firebase authentication will fail at runtime. " +
                "Set GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json " +
                "before starting in production.");
        }
    }
}
