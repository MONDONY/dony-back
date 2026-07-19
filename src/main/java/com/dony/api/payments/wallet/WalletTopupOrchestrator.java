package com.dony.api.payments.wallet;

import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.MinorUnits;
import com.dony.api.common.money.Money;
import com.dony.api.payments.wallet.dto.WalletTopupRequest;
import com.dony.api.payments.wallet.dto.WalletTopupResponse;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class WalletTopupOrchestrator {

    private final CurrencyRegistry currencyRegistry;

    public WalletTopupOrchestrator(CurrencyRegistry currencyRegistry) {
        this.currencyRegistry = currencyRegistry;
    }

    public WalletTopupResponse initiate(UUID userId, WalletTopupRequest request) {
        return switch (request.getPaymentMethod()) {
            case "STRIPE" -> initiateStripe(userId, request.getAmount());
            case "WAVE" -> initiateWave(userId, request.getAmount());
            case "ORANGE_MONEY" -> initiateOrangeMoney(userId, request.getAmount());
            default -> throw new IllegalArgumentException(
                "Mode de paiement inconnu : " + request.getPaymentMethod());
        };
    }

    private WalletTopupResponse initiateStripe(UUID userId, BigDecimal amount) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(MinorUnits.toMinorExact(new Money(amount, "EUR"), currencyRegistry))
                .setCurrency("eur")
                .putMetadata("wallet_topup", "true")
                .putMetadata("user_id", userId.toString())
                .build();
            PaymentIntent pi = PaymentIntent.create(params);
            return new WalletTopupResponse(pi.getClientSecret(), null);
        } catch (Exception e) {
            throw new RuntimeException("Erreur Stripe topup", e);
        }
    }

    // ⚠️ DEVISE (spec devise §5.3) : `amount` est en EUR. Au branchement de la
    // vraie API Wave/OM, convertir via PeggedFxRateProvider + MoneyRounding
    // (wallet XOF/XAF) — ne JAMAIS envoyer le montant EUR brut dans l'URL.
    private WalletTopupResponse initiateWave(UUID userId, BigDecimal amount) {
        String redirectUrl = "https://wave.com/pay?amount=" + amount + "&ref=dony-" + userId;
        return new WalletTopupResponse(null, redirectUrl);
    }

    // ⚠️ DEVISE (spec devise §5.3) : `amount` est en EUR. Au branchement de la
    // vraie API Wave/OM, convertir via PeggedFxRateProvider + MoneyRounding
    // (wallet XOF/XAF) — ne JAMAIS envoyer le montant EUR brut dans l'URL.
    private WalletTopupResponse initiateOrangeMoney(UUID userId, BigDecimal amount) {
        String redirectUrl = "https://orange-money.com/pay?amount=" + amount + "&ref=dony-" + userId;
        return new WalletTopupResponse(null, redirectUrl);
    }
}
