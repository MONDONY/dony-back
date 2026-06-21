package com.dony.api.common;

import com.dony.api.payments.cash.exception.CommissionChargeFailedException;
import com.dony.api.payments.cash.exception.CommissionMethodMissingException;
import com.dony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException;
import com.dony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import io.sentry.Sentry;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String BASE_TYPE = "https://dony.app/errors/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        problem.setType(URI.create(BASE_TYPE + "validation"));
        problem.setTitle("Validation Error");

        Map<String, String> violations = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        f -> f.getDefaultMessage() != null ? f.getDefaultMessage() : "invalid",
                        (a, b) -> a
                ));
        problem.setProperty("violations", violations);
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "validation"));
        problem.setTitle("Constraint Violation");
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "unauthorized"));
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Access denied");
        problem.setType(URI.create(BASE_TYPE + "forbidden"));
        problem.setTitle("Forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(DonyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(DonyNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "not-found"));
        problem.setTitle("Not Found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DonyBusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(DonyBusinessException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ex.getStatus(), ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + ex.getErrorCode()));
        problem.setTitle(ex.getTitle());
        problem.setProperty("code", ex.getErrorCode());
        ex.getProperties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ex.getStatusCode(), reason != null ? reason : ex.getMessage());

        if (reason != null && reason.contains("/")) {
            // Structured error code like "request/expired" or "negotiation/duplicate-thread"
            problem.setType(URI.create(BASE_TYPE + reason));
            String[] parts = reason.split("/", 2);
            String title = parts.length > 1
                    ? capitalize(parts[1].replace("-", " "))
                    : capitalize(parts[0].replace("-", " "));
            problem.setTitle(title);
        } else {
            problem.setType(URI.create(BASE_TYPE + "generic"));
            problem.setTitle(reason != null ? reason : ex.getStatusCode().toString());
        }

        return ResponseEntity.status(ex.getStatusCode()).body(problem);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex) {
        log.error("HttpMessageNotReadableException: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed request payload");
        problem.setType(URI.create(BASE_TYPE + "malformed-request"));
        problem.setTitle("Bad Request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(TravelerNotEligibleForPaymentException.class)
    public ResponseEntity<ProblemDetail> handleTravelerNotEligible(TravelerNotEligibleForPaymentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create(BASE_TYPE + "traveler-not-eligible"));
        problem.setTitle("Traveler Not Eligible");
        problem.setProperty("code", "traveler-not-eligible");
        problem.setProperty("travelerId", ex.getTravelerId().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(CommissionMethodMissingException.class)
    public ProblemDetail handleCommissionMethodMissing(CommissionMethodMissingException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create(BASE_TYPE + "commission-method-missing"));
        pd.setTitle("Méthode de commission requise");
        return pd;
    }

    @ExceptionHandler(InvalidPaymentMethodForAnnouncementException.class)
    public ProblemDetail handleInvalidPaymentMethod(InvalidPaymentMethodForAnnouncementException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create(BASE_TYPE + "invalid-payment-method-for-announcement"));
        pd.setTitle("Mode de paiement non autorisé");
        return pd;
    }

    @ExceptionHandler(CommissionChargeFailedException.class)
    public ProblemDetail handleCommissionChargeFailed(CommissionChargeFailedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
        pd.setType(URI.create(BASE_TYPE + "commission-charge-failed"));
        pd.setTitle("Débit de la commission refusé");
        return pd;
    }

    /**
     * Optimistic-lock conflict (e.g. two concurrent finalizes of the same negotiation
     * thread — /checkout vs Stripe webhook). The loser maps to 409 instead of 500 so
     * the client can simply re-read the now-finalized resource.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "La ressource a été modifiée simultanément, réessayez");
        problem.setType(URI.create(BASE_TYPE + "concurrent-update"));
        problem.setTitle("Concurrent Update");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        Sentry.captureException(ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create(BASE_TYPE + "internal-error"));
        problem.setTitle("Internal Server Error");
        return ResponseEntity.internalServerError().body(problem);
    }
}
