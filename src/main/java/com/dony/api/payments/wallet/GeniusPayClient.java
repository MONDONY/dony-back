package com.dony.api.payments.wallet;

import com.dony.api.common.DonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client HTTP pur pour l'API marchande GeniusPay (mode direct uniquement —
 * payment_method toujours renseigné, jamais le checkout hébergé). Ne traite
 * QUE la recharge du wallet voyageur — jamais le prix du transport
 * (spec devise §1.2, GeniusPay design doc 2026-07-19).
 */
@Component
public class GeniusPayClient {

    private static final Logger log = LoggerFactory.getLogger(GeniusPayClient.class);

    private final RestTemplate restTemplate;
    private final GeniusPayProperties props;

    public GeniusPayClient(@Qualifier("geniusPayRestTemplate") RestTemplate restTemplate,
                           GeniusPayProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public GeniusPayPaymentResult createPayment(long amountMinor, String currency, String paymentMethod,
                                                String phoneNumber, String description,
                                                String successUrl, String errorUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", props.apiKey());
        headers.set("X-API-Secret", props.apiSecret());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountMinor);
        body.put("currency", currency);
        body.put("payment_method", paymentMethod);
        body.put("description", description);
        body.put("customer", Map.of("phone", phoneNumber));
        // GeniusPay n'accepte que des URLs http(s) (un schéma custom type "dony://"
        // est rejeté) — le retour vers l'app passe donc par une page de rebond
        // HTTPS (GeniusPayReturnController) qui redirige ensuite vers dony://.
        body.put("success_url", successUrl);
        body.put("error_url", errorUrl);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    props.baseUrl() + "/payments", HttpMethod.POST, request, Map.class);
            Map<String, Object> result = response.getBody();
            if (result == null || result.get("data") == null) {
                log.error("GeniusPay createPayment returned empty body/data");
                throw new DonyBusinessException(HttpStatus.BAD_GATEWAY,
                        "geniuspay-empty-response", "Bad Gateway", "Réponse GeniusPay vide");
            }
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            String reference = (String) data.get("reference");
            String paymentUrl = (String) data.get("checkout_url");
            return new GeniusPayPaymentResult(reference, paymentUrl);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("GeniusPay createPayment HTTP error: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new DonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "geniuspay-error", "GeniusPay Error",
                    "Erreur GeniusPay lors de la création du paiement");
        } catch (ResourceAccessException e) {
            log.error("GeniusPay createPayment timeout: {}", e.getMessage());
            throw new DonyBusinessException(HttpStatus.GATEWAY_TIMEOUT,
                    "geniuspay-timeout", "Gateway Timeout", "GeniusPay API timeout");
        }
    }
}
