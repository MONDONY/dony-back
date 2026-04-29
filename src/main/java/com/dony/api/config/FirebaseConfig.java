package com.dony.api.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.service-account-path:}")
    private String serviceAccountPath;

    @Bean
    public Firestore firestore() {
        if (serviceAccountPath.isBlank() || FirebaseApp.getApps().isEmpty()) {
            log.warn("Firestore bean unavailable — Firebase not initialized (test/ci mode)");
            return null;
        }
        return FirestoreClient.getFirestore();
    }

    @PostConstruct
    public void initializeFirebase() {
        if (serviceAccountPath.isBlank()) {
            log.warn("Firebase service account path is empty — Firebase disabled (test/ci mode)");
            return;
        }
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }
        try {
            String path = serviceAccountPath.replaceFirst("^classpath:", "");
            InputStream stream = new ClassPathResource(path).getInputStream();
            GoogleCredentials credentials = GoogleCredentials.fromStream(stream);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase initialized — project: {}", getProjectId(path));
        } catch (IOException e) {
            log.error("Failed to initialize Firebase: {}", e.getMessage());
            throw new IllegalStateException("Firebase initialization failed", e);
        }
    }

    private String getProjectId(String path) {
        try {
            InputStream stream = new ClassPathResource(path).getInputStream();
            byte[] bytes = stream.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            int idx = content.indexOf("\"project_id\"");
            if (idx == -1) return "unknown";
            int start = content.indexOf("\"", idx + 13) + 1;
            int end = content.indexOf("\"", start);
            return content.substring(start, end);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
