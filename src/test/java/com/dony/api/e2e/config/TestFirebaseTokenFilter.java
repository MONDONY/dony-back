package com.dony.api.e2e.config;

import com.dony.api.admin.account.AdminAuthService;
import com.dony.api.auth.FirebaseTokenFilter;
import com.dony.api.auth.UserLinkerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * E2E-only replacement for FirebaseTokenFilter. Reads X-Test-UID / X-Test-Roles
 * headers and populates the SecurityContext directly. Registered only via
 * E2EMockConfig @Bean so it runs inside Spring Security's filter chain (not as a
 * servlet filter), avoiding the OncePerRequestFilter double-registration pitfall.
 *
 * <p>The credentials carry a stub {@link FirebaseToken} whose
 * {@code firebase.sign_in_provider} claim defaults to {@code "phone"} (overridable
 * per-request via the {@code X-Test-Provider} header). {@code AuthController.register}
 * reads it to pick the registration branch — without it, {@code createUser} hits the
 * {@code default} switch case and rejects every registration with 422
 * {@code invalid-provider}, leaving no user in the DB (→ 404 on every later call).
 */
public class TestFirebaseTokenFilter extends FirebaseTokenFilter {

    public TestFirebaseTokenFilter(UserLinkerService userLinkerService,
                                   ObjectMapper objectMapper,
                                   AdminAuthService adminAuthService) {
        super(userLinkerService, objectMapper, adminAuthService);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String testUid = request.getHeader("X-Test-UID");
        String testRoles = request.getHeader("X-Test-Roles");

        if (testUid != null && !testUid.isBlank()) {
            String provider = request.getHeader("X-Test-Provider");
            FirebaseToken token = stubToken(provider != null && !provider.isBlank() ? provider.trim() : "phone",
                    request.getHeader("X-Test-Email"));
            List<SimpleGrantedAuthority> authorities = parseRoles(testRoles);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(testUid.trim(), token, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }

    /** Builds a minimal FirebaseToken stub carrying the sign-in provider (and optional email). */
    private FirebaseToken stubToken(String provider, String email) {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getClaims()).thenReturn(Map.of("firebase", Map.of("sign_in_provider", provider)));
        Mockito.when(token.getEmail()).thenReturn(email);
        return token;
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
