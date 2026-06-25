package com.dony.api.auth;

import com.dony.api.admin.account.AdminAuthService;
import com.dony.api.admin.account.AdminAuthorities;
import com.dony.api.admin.account.AdminPermission;
import com.dony.api.admin.account.AdminPrincipal;
import com.dony.api.admin.account.AdminRole;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class FirebaseTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DEV_BYPASS_TOKEN = "dev-super-admin";
    private static final UUID DEV_ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserLinkerService userLinkerService;
    private final ObjectMapper objectMapper;
    private final AdminAuthService adminAuthService;
    private final boolean devAuthBypassEnabled;

    public FirebaseTokenFilter(UserLinkerService userLinkerService,
                               ObjectMapper objectMapper,
                               AdminAuthService adminAuthService,
                               @Value("${dony.dev.auth-bypass:false}") boolean devAuthBypassEnabled) {
        this.userLinkerService = userLinkerService;
        this.objectMapper = objectMapper;
        this.adminAuthService = adminAuthService;
        this.devAuthBypassEnabled = devAuthBypassEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            boolean blocked = authenticateToken(token, request, response);
            if (blocked) return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * @return true if the request was blocked (admin-only route accessed by non-admin, or suspended/banned user),
     *         false to continue
     */
    private boolean authenticateToken(String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (devAuthBypassEnabled && DEV_BYPASS_TOKEN.equals(token)) {
            injectDevSuperAdmin();
            return false;
        }

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

        // Admin fast-path: resolve admin before normal user lookup
        Optional<AdminAuthorities> adminOpt = adminAuthService.resolve(uid);
        if (adminOpt.isPresent()) {
            AdminAuthorities admin = adminOpt.get();
            UsernamePasswordAuthenticationToken adminAuth =
                    new UsernamePasswordAuthenticationToken(
                            new AdminPrincipal(admin.adminId(), admin.login(), admin.role(), admin.mustChangePassword(), uid),
                            decoded,
                            admin.authorities()
                    );
            SecurityContextHolder.getContext().setAuthentication(adminAuth);
            return false; // not blocked
        }
        // If request targets admin routes and caller is not an admin → 403
        if (request.getRequestURI().startsWith("/admin/")) {
            writeForbidden(response, "Accès réservé aux administrateurs");
            return true;
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

    private void injectDevSuperAdmin() {
        Set<GrantedAuthority> authorities = EnumSet.allOf(AdminPermission.class).stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

        AdminPrincipal principal = new AdminPrincipal(DEV_ADMIN_ID, "dev-admin", AdminRole.SUPER_ADMIN, false, "dev-uid");
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void setAuthentication(String uid, FirebaseToken decoded,
                                   List<SimpleGrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(uid, decoded, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void writeForbidden(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        problem.setType(URI.create("https://dony.app/errors/access-denied"));
        problem.setTitle("Access Denied");

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
