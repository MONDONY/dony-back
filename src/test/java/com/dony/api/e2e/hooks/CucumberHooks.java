package com.dony.api.e2e.hooks;

import com.dony.api.common.StorageService;
import io.cucumber.java.Before;
import io.restassured.RestAssured;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Global hooks run before every scenario.
 */
public class CucumberHooks {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StorageService storageService;

    @Before(order = 0)
    public void configureRestAssured() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Before(order = 1)
    public void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    tracking_events,
                    rematch_suggestions,
                    cancellations,
                    payments,
                    bids,
                    announcements,
                    audit_log,
                    kyc_schema.kyc_verifications,
                    user_roles,
                    users
                CASCADE
                """);
    }

    @Before(order = 2)
    public void resetMocks() {
        Mockito.reset(storageService);
        Mockito.when(storageService.generatePresignedUrl(Mockito.anyString(), Mockito.any()))
               .thenReturn("https://fake-s3.dony.test/photo.jpg");
        try {
            Mockito.when(storageService.uploadFile(Mockito.any(), Mockito.anyString()))
                   .thenReturn("tracking/test/fake-key.jpg");
        } catch (Exception ignored) {}
    }
}
