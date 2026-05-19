package com.dony.api.emailotp;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class ResendEmailService {

    private final RestClient restClient;
    private final String fromAddress;
    private final String otpTemplate;

    public ResendEmailService(EmailOtpProperties props) {
        this.fromAddress = props.getFromAddress();
        this.otpTemplate = props.getOtpTemplate();
        this.restClient = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getResendApiKey())
                .build();
    }

    public void sendOtp(String to, String code) {
        Map<String, Object> payload = Map.of(
                "from", fromAddress,
                "to", List.of(to),
                "subject", "Ton code dony",
                "text", String.format(otpTemplate, code)
        );
        restClient.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
