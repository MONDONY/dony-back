package com.dony.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@Component
public class FirebaseTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserLinkerService userLinkerService;
    private final ObjectMapper objectMapper;

    public FirebaseTokenFilter(UserLinkerService userLinkerService, ObjectMapper objectMapper) {
        this.userLinkerService = userLinkerService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            boolean blocked = authenticateToken(token, response);
            if (blocked) return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * @return true if the request was blocked (suspended/banned user), false to continue
     */
    private boolean authenticateToken(String token, HttpServletResponse response) throws IOException {
        if (!isFirebaseReady()) return false;

        FirebaseToken decoded;
        String uid;
        try {
            decoded = FirebaseAuth.getInstance().verifyIdToken(token);
            uid = decoded.getUid();
        } catch (FirebaseAuthException e) {
            log.debug("Invalid Firebase token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            return false;
        }

        try {
            UserEntity user = userLinkerService.resolveAndLink(uid, decoded).orElse(null);

            if (user == null) {
                // New user — not yet registered; allow with empty roles (registration flow)
                setAuthentication(uid, decoded, List.of());
                return false;
            }

            if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.BANNED) {
                writeForbidden(response, "Votre compte est suspendu ou banni");
                return true;
            }

            List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                    .toList();

            setAuthentication(uid, decoded, authorities);
        } catch (Exception e) {
            log.warn("Could not load user from DB for uid {}: {}", uid, e.getMessage());
            SecurityContextHolder.clearContext(); // do NOT grant access on DB failure
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service temporarily unavailable");
            return true;
        }

        return false;
    }

    private void setAuthentication(String uid, FirebaseToken decoded,
                                   List<SimpleGrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(uid, decoded, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void writeForbidden(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        problem.setType(URI.create("https://dony.app/errors/account-suspended"));
        problem.setTitle("Account Suspended");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private boolean isFirebaseReady() {
        try {
            return !FirebaseApp.getApps().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
