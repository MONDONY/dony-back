package com.dony.api.payments.wallet;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.wallet.dto.WalletTopupRequest;
import com.dony.api.payments.wallet.dto.WalletTopupResponse;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class WalletTopupOrchestrator {

    public WalletTopupResponse initiate(UUID userId, WalletTopupRequest request) {
        return switch (request.getPaymentMethod()) {
            case "STRIPE" -> initiateStripe(userId, request.getAmount());
            case "WAVE", "ORANGE_MONEY" -> throw new DonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "mobile-money-topup-retired", "Mobile Money Topup Retired",
                "Le rechargement par mobile money n'est plus disponible pour le moment. "
                + "Choisissez Carte bancaire.");
            default -> throw new IllegalArgumentException(
                "Mode de paiement inconnu : " + request.getPaymentMethod());
        };
    }

    private WalletTopupResponse initiateStripe(UUID userId, BigDecimal amount) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount.multiply(BigDecimal.valueOf(100)).longValue())
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
}
