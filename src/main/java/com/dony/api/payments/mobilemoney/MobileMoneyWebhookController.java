package com.dony.api.payments.mobilemoney;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.cash.PaymentMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/webhooks/mobile-money")
public class MobileMoneyWebhookController {

    private static final Set<PaymentMethod> SUPPORTED_MM_PROVIDERS =
            Set.of(PaymentMethod.WAVE, PaymentMethod.ORANGE_MONEY);

    private final MobileMoneyPaymentService service;

    public MobileMoneyWebhookController(MobileMoneyPaymentService service) {
        this.service = service;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Void> receiveWebhook(
            @PathVariable String provider,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawPayload) {

        PaymentMethod pm;
        try {
            pm = PaymentMethod.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST,
                    "unknown-mm-provider", "Unknown Provider",
                    "Provider Mobile Money inconnu : " + provider);
        }

        if (!SUPPORTED_MM_PROVIDERS.contains(pm)) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST,
                    "not-mm-provider", "Not MM Provider",
                    provider + " n'est pas un provider Mobile Money");
        }

        service.handleWebhook(pm, rawPayload, signature != null ? signature : "");
        return ResponseEntity.ok().build();
    }
}
