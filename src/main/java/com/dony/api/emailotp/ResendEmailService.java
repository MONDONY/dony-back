package com.dony.api.emailotp;

import com.dony.api.common.DonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class ResendEmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    private final RestClient restClient;
    private final String fromAddress;
    private final String otpTemplate;
    private final boolean devProfile;

    @Autowired
    public ResendEmailService(EmailOtpProperties props, Environment env) {
        this.fromAddress = props.getFromAddress();
        this.otpTemplate = props.getOtpTemplate();
        this.devProfile = Arrays.asList(env.getActiveProfiles()).contains("dev");
        this.restClient = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getResendApiKey())
                .build();
    }

    ResendEmailService(String fromAddress, String otpTemplate, RestClient restClient) {
        this.fromAddress = fromAddress;
        this.otpTemplate = otpTemplate;
        this.restClient = restClient;
        this.devProfile = false;
    }

    public void sendOtp(String to, String code) {
        Map<String, Object> payload = Map.of(
                "from", fromAddress,
                "to", List.of(to),
                "subject", "Ton code dony",
                "text", String.format(otpTemplate, code)
        );
        try {
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Resend API error sending OTP to {}: {}", to, e.getMessage());
            if (devProfile) {
                // En dev, l'envoi réel peut échouer (domaine Resend non vérifié).
                // On logge le code pour permettre la connexion locale sans email réel.
                log.warn("📧 [DEV] Code OTP pour {} : {}", to, code);
                return;
            }
            throw new DonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, "email-service-error",
                    "Email Service Error", "L'envoi de l'email a échoué, veuillez réessayer");
        }
    }
}
