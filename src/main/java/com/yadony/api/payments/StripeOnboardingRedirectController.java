package com.yadony.api.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints intermédiaires requis par Stripe : l'API AccountLink n'accepte que des URLs HTTPS,
 * pas les deep links (yadony://...). Ces endpoints reçoivent la redirection de Stripe après
 * l'onboarding et redirigent vers le deep link Flutter.
 *
 * Flux : Stripe → GET /payments/onboarding/return → 302 yadony://stripe/onboarding/complete
 */
@RestController
@RequestMapping("/payments/onboarding")
public class StripeOnboardingRedirectController {

    @Value("${yadony.stripe.connect.deep-link-return:yadony://stripe/onboarding/complete}")
    private String deepLinkReturn;

    @Value("${yadony.stripe.connect.deep-link-refresh:yadony://stripe/onboarding/refresh}")
    private String deepLinkRefresh;

    @GetMapping("/return")
    public ResponseEntity<Void> onboardingReturn() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, deepLinkReturn)
                .build();
    }

    @GetMapping("/refresh")
    public ResponseEntity<Void> onboardingRefresh() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, deepLinkRefresh)
                .build();
    }
}
