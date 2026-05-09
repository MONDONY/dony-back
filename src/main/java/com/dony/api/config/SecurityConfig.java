package com.dony.api.config;

import com.dony.api.auth.FirebaseTokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.net.URI;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final FirebaseTokenFilter firebaseTokenFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(FirebaseTokenFilter firebaseTokenFilter, ObjectMapper objectMapper) {
        this.firebaseTokenFilter = firebaseTokenFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/auth/**",
                    "/actuator/health",
                    "/actuator/info",
                    "/config/**",
                    "/kyc/webhook",
                    "/payments/webhook",
                    "/ratings/recipient",
                    "/ratings/user/**",
                    "/tracking/search",
                    "/tracking/public/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    // Internal messaging notify: kept in permitAll because the caller (Firebase Functions)
                    // does not carry a Firebase user token. Security is enforced at the controller level
                    // via constant-time comparison of X-Internal-Secret header (option b from the fix spec).
                    "/internal/messaging/notify",
                    // Stripe redirige ici après onboarding — pas de token Firebase (browser Stripe)
                    "/payments/onboarding/return",
                    "/payments/onboarding/refresh"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(firebaseTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                // 401 — missing or invalid token
                .authenticationEntryPoint((request, response, authException) -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                            HttpStatus.UNAUTHORIZED, "Authentication required");
                    problem.setType(URI.create("https://dony.app/errors/unauthorized"));
                    problem.setTitle("Unauthorized");

                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), problem);
                })
                // 403 — authenticated but not enough permissions
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                            HttpStatus.FORBIDDEN, "Access denied");
                    problem.setType(URI.create("https://dony.app/errors/forbidden"));
                    problem.setTitle("Forbidden");

                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    objectMapper.writeValue(response.getWriter(), problem);
                })
            );

        return http.build();
    }
}
