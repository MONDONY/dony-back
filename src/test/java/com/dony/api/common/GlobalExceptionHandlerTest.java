package com.dony.api.common;

import io.sentry.Sentry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("GlobalExceptionHandler — tests unitaires")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("handleValidation()")
    class ValidationTests {

        @Test
        @DisplayName("erreurs de validation → 422 avec violations")
        void handleValidation_returns422WithViolations() {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("obj", "phoneNumber", "Le numéro est invalide");

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

            ResponseEntity<ProblemDetail> response = handler.handleValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            ProblemDetail body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTitle()).isEqualTo("Validation Error");
            assertThat(body.getType().toString()).contains("validation");
            @SuppressWarnings("unchecked")
            var violations = (java.util.Map<String, String>) body.getProperties().get("violations");
            assertThat(violations).containsKey("phoneNumber");
            assertThat(violations.get("phoneNumber")).isEqualTo("Le numéro est invalide");
        }

        @Test
        @DisplayName("plusieurs erreurs sur le même champ → premier message conservé")
        void handleValidation_multipleSameField_keepFirstMessage() {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError error1 = new FieldError("obj", "email", "Email invalide");
            FieldError error2 = new FieldError("obj", "email", "Email trop long");

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(error1, error2));

            ResponseEntity<ProblemDetail> response = handler.handleValidation(ex);

            @SuppressWarnings("unchecked")
            var violations = (java.util.Map<String, String>) response.getBody().getProperties().get("violations");
            assertThat(violations).hasSize(1);
            assertThat(violations.get("email")).isEqualTo("Email invalide");
        }
    }

    @Nested
    @DisplayName("handleConstraintViolation()")
    class ConstraintViolationTests {

        @Test
        @DisplayName("constraint violation → 422 avec message")
        void handleConstraintViolation_returns422() {
            @SuppressWarnings("unchecked")
            Set<ConstraintViolation<?>> violations = Set.of();
            ConstraintViolationException ex = new ConstraintViolationException("Constraint failed", violations);

            ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().getType().toString()).contains("validation");
        }
    }

    @Nested
    @DisplayName("handleAuthentication()")
    class AuthenticationTests {

        @Test
        @DisplayName("AuthenticationException → 401 UNAUTHORIZED")
        void handleAuthentication_returns401() {
            AuthenticationException ex = mock(AuthenticationException.class);
            when(ex.getMessage()).thenReturn("Token invalide");

            ResponseEntity<ProblemDetail> response = handler.handleAuthentication(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().getTitle()).isEqualTo("Unauthorized");
            assertThat(response.getBody().getType().toString()).contains("unauthorized");
        }
    }

    @Nested
    @DisplayName("handleAccessDenied()")
    class AccessDeniedTests {

        @Test
        @DisplayName("AccessDeniedException → 403 FORBIDDEN")
        void handleAccessDenied_returns403() {
            AccessDeniedException ex = new AccessDeniedException("Accès refusé");

            ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getTitle()).isEqualTo("Forbidden");
            assertThat(response.getBody().getType().toString()).contains("forbidden");
        }
    }

    @Nested
    @DisplayName("handleNotFound()")
    class NotFoundTests {

        @Test
        @DisplayName("DonyNotFoundException → 404 NOT_FOUND")
        void handleNotFound_returns404() {
            DonyNotFoundException ex = new DonyNotFoundException("Ressource introuvable");

            ResponseEntity<ProblemDetail> response = handler.handleNotFound(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().getTitle()).isEqualTo("Not Found");
            assertThat(response.getBody().getType().toString()).contains("not-found");
        }
    }

    @Nested
    @DisplayName("handleBusiness()")
    class BusinessTests {

        @Test
        @DisplayName("DonyBusinessException 409 → 409 CONFLICT avec errorCode dans type")
        void handleBusiness_returns409WithTypeUri() {
            DonyBusinessException ex = new DonyBusinessException(
                    HttpStatus.CONFLICT, "phone-already-exists",
                    "Phone Already Exists", "Ce numéro existe déjà");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getTitle()).isEqualTo("Phone Already Exists");
            assertThat(response.getBody().getType().toString()).contains("phone-already-exists");
            assertThat(response.getBody().getDetail()).isEqualTo("Ce numéro existe déjà");
        }

        @Test
        @DisplayName("DonyBusinessException 403 → 403 FORBIDDEN")
        void handleBusiness_403_returnsForbidden() {
            DonyBusinessException ex = new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden-role", "Forbidden Role", "Rôle interdit");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("DonyBusinessException 422 → 422 UNPROCESSABLE_ENTITY")
        void handleBusiness_422_returnsUnprocessable() {
            DonyBusinessException ex = new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "value-exceeds-limit",
                    "Value Exceeds Limit", "Valeur maximum : 500 €");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("DonyBusinessException 500 → 500 INTERNAL_SERVER_ERROR")
        void handleBusiness_500_returnsInternalServerError() {
            DonyBusinessException ex = new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "firebase-delete-failed",
                    "Firebase Delete Failed", "Erreur Firebase");

            ResponseEntity<ProblemDetail> response = handler.handleBusiness(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("handleGeneric()")
    class GenericExceptionTests {

        @Test
        @DisplayName("exception inattendue → 500 + Sentry capturé")
        void handleGeneric_returns500AndCapturesToSentry() {
            RuntimeException ex = new RuntimeException("Erreur inattendue");

            try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
                sentryMock.when(() -> Sentry.captureException(any())).thenAnswer(inv -> null);

                ResponseEntity<ProblemDetail> response = handler.handleGeneric(ex);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(response.getBody().getTitle()).isEqualTo("Internal Server Error");
                assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
                assertThat(response.getBody().getType().toString()).contains("internal-error");

                sentryMock.verify(() -> Sentry.captureException(ex));
            }
        }

        @Test
        @DisplayName("NullPointerException → 500 toujours retourné")
        void handleGeneric_nullPointer_returns500() {
            NullPointerException npe = new NullPointerException("null ref");

            try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
                sentryMock.when(() -> Sentry.captureException(any())).thenAnswer(inv -> null);

                ResponseEntity<ProblemDetail> response = handler.handleGeneric(npe);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
    }
}
