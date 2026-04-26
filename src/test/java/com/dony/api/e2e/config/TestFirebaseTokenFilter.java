package com.dony.api.e2e.config;

import com.dony.api.auth.FirebaseTokenFilter;
import com.dony.api.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * E2E-only replacement for FirebaseTokenFilter. Reads X-Test-UID / X-Test-Roles
 * headers and populates the SecurityContext directly. Registered only via
 * E2EMockConfig @Bean so it runs inside Spring Security's filter chain (not as a
 * servlet filter), avoiding the OncePerRequestFilter double-registration pitfall.
 */
public class TestFirebaseTokenFilter extends FirebaseTokenFilter {

    public TestFirebaseTokenFilter(UserRepository userRepository, ObjectMapper objectMapper) {
        super(userRepository, objectMapper);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String testUid = request.getHeader("X-Test-UID");
        String testRoles = request.getHeader("X-Test-Roles");

        if (testUid != null && !testUid.isBlank()) {
            List<SimpleGrantedAuthority> authorities = parseRoles(testRoles);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(testUid.trim(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> parseRoles(String testRoles) {
        if (testRoles == null || testRoles.isBlank()) return List.of();
        return Arrays.stream(testRoles.split(","))
                .map(String::trim)
                .filter(r -> !r.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
