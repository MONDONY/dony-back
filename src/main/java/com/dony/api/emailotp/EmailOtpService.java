package com.dony.api.emailotp;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@Transactional
public class EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);

    private static final int MAX_SENDS_PER_WINDOW = 3;
    private static final int RATE_WINDOW_MINUTES  = 5;
    private static final int MAX_ATTEMPTS         = 5;
    private static final int OTP_VALID_MINUTES    = 10;

    private final EmailOtpRepository emailOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final ResendEmailService resendEmailService;
    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;

    public EmailOtpService(EmailOtpRepository emailOtpRepository,
                           PasswordEncoder passwordEncoder,
                           ResendEmailService resendEmailService,
                           @Autowired(required = false) FirebaseAuth firebaseAuth,
                           UserRepository userRepository) {
        this.emailOtpRepository = emailOtpRepository;
        this.passwordEncoder    = passwordEncoder;
        this.resendEmailService = resendEmailService;
        this.firebaseAuth       = firebaseAuth;
        this.userRepository     = userRepository;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Instant sendOtp(String email) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(RATE_WINDOW_MINUTES);
        if (emailOtpRepository.countByEmailSince(email, since) >= MAX_SENDS_PER_WINDOW) {
            throw new DonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "rate-limit",
                    "Too Many Requests", "Trop de tentatives, réessaie dans 5 min");
        }

        String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(OTP_VALID_MINUTES);

        EmailOtpEntity entity = new EmailOtpEntity();
        entity.setEmail(email);
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(expiresAt);
        emailOtpRepository.save(entity);

        resendEmailService.sendOtp(email, code);

        return expiresAt.toInstant(ZoneOffset.UTC);
    }

    public String verifyOtp(String email, String code) {
        log.info("verifyOtp: email='{}' length={}", email, email == null ? -1 : email.length());
        EmailOtpEntity token = emailOtpRepository
                .findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.BAD_REQUEST, "otp-invalid",
                        "Invalid OTP", "Code invalide ou expiré"));

        if (token.getAttempts() >= MAX_ATTEMPTS) {
            throw new DonyBusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "otp-attempts-exceeded",
                    "Too Many Attempts", "Trop de tentatives échouées");
        }

        // BCrypt appelé avant le check expiration pour éviter les timing attacks
        boolean validCode = passwordEncoder.matches(code, token.getCodeHash());

        if (LocalDateTime.now(ZoneOffset.UTC).isAfter(token.getExpiresAt())) {
            throw new DonyBusinessException(
                    HttpStatus.BAD_REQUEST, "otp-expired",
                    "OTP Expired", "Code expiré");
        }

        if (!validCode) {
            token.setAttempts(token.getAttempts() + 1);
            emailOtpRepository.save(token);
            throw new DonyBusinessException(
                    HttpStatus.BAD_REQUEST, "otp-invalid",
                    "Invalid OTP", "Code invalide");
        }

        token.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
        emailOtpRepository.save(token);

        if (firebaseAuth == null) {
            log.warn("FirebaseAuth not available — returning null custom token (test mode)");
            return null;
        }
        try {
            // Si l'utilisateur existe déjà, on crée le token avec son firebase_uid existant
            // pour que GET /auth/me fonctionne même si le compte a été créé via un autre provider
            String uid = userRepository.findByEmail(email)
                    .map(u -> u.getFirebaseUid())
                    .orElse(email);
            return firebaseAuth.createCustomToken(uid);
        } catch (FirebaseAuthException e) {
            throw new DonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error",
                    "Firebase Error", "Erreur lors de la création du token");
        }
    }
}
