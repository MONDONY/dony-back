package com.dony.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FirebaseTokenFilter — credentials storage")
class FirebaseTokenFilterTest {

    @Mock private UserRepository userRepository;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;
    @Mock private FirebaseAuth mockFirebaseAuth;
    @Mock private FirebaseToken mockToken;

    private static final String FIREBASE_UID = "uid-test-001";

    private FirebaseTokenFilter buildFilter() {
        return new FirebaseTokenFilter(userRepository, new ObjectMapper());
    }

    private UserEntity makeUser(UserStatus status) {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid(FIREBASE_UID);
        u.setStatus(status);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try { Field f = c.getDeclaredField("id"); f.setAccessible(true); f.set(entity, id); return; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("user inscrit → credentials = FirebaseToken décodé")
    void registeredUser_credentialsIsDecodedToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userRepository.findByFirebaseUid(FIREBASE_UID))
                .thenReturn(Optional.of(makeUser(UserStatus.ACTIVE)));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = (UsernamePasswordAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getCredentials()).isEqualTo(mockToken);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("user non inscrit → credentials = decoded FirebaseToken (registration flow)")
    void unregisteredUser_credentialsIsDecodedToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = (UsernamePasswordAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getCredentials()).isEqualTo(mockToken);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("user SUSPENDED → 403, filterChain non appelé")
    void suspendedUser_returns403() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userRepository.findByFirebaseUid(FIREBASE_UID))
                .thenReturn(Optional.of(makeUser(UserStatus.SUSPENDED)));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        verify(response).setStatus(SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("DB failure → 503, SecurityContext vidé")
    void dbFailure_returns503() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userRepository.findByFirebaseUid(FIREBASE_UID))
                .thenThrow(new RuntimeException("DB down"));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        verify(response).sendError(eq(503), anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(any(), any());
    }
}
