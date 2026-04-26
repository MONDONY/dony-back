package com.dony.api.e2e.config;

import com.dony.api.auth.FirebaseTokenFilter;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * E2E test wiring:
 * - TestFirebaseTokenFilter: @Bean @Primary replaces the real Firebase filter.
 *   NOT a @Component so it only runs inside the Spring Security filter chain
 *   (avoids the OncePerRequestFilter double-registration 401 bug).
 * - FirebaseTokenFilter servlet registration: disabled so the base bean does not
 *   run as a servlet filter alongside the security chain.
 * - StorageService: Mockito mock (no real S3 in tests).
 */
@TestConfiguration
@Profile("e2e")
public class E2EMockConfig {

    @Bean
    @Primary
    public FirebaseTokenFilter firebaseTokenFilter(UserRepository userRepository,
                                                   ObjectMapper objectMapper) {
        return new TestFirebaseTokenFilter(userRepository, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<FirebaseTokenFilter> disableFirebaseFilterServletRegistration(
            FirebaseTokenFilter firebaseTokenFilter) {
        FilterRegistrationBean<FirebaseTokenFilter> reg =
                new FilterRegistrationBean<>(firebaseTokenFilter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    @Primary
    public StorageService storageService() {
        StorageService mock = Mockito.mock(StorageService.class);
        Mockito.when(mock.generatePresignedUrl(Mockito.anyString(), Mockito.any()))
               .thenReturn("https://fake-s3.dony.test/photo.jpg");
        try {
            Mockito.when(mock.uploadFile(Mockito.any(), Mockito.anyString()))
                   .thenReturn("tracking/test/fake-key.jpg");
        } catch (Exception ignored) {}
        return mock;
    }
}
