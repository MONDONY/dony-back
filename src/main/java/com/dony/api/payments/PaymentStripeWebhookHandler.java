package com.dony.api.payments;

import com.dony.api.common.stripe.StripeWebhookHandler;
import com.dony.api.payments.cash.CashCommissionWebhookHandler;
import com.dony.api.payments.chargeback.ChargebackService;
import com.dony.api.payments.wallet.WalletService;
import com.dony.api.payments.wallet.WalletTransactionType;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;

@Component
public class PaymentStripeWebhookHandler implements StripeWebhookHandler {

    private static final Set<String> SUPPORTED = Set.of(
            "account.updated",
            "payment_intent.amount_capturable_updated",
            "payment_intent.payment_failed",
            "payment_intent.canceled",
            "charge.refunded",
            "setup_intent.succeeded",
            "payment_intent.succeeded",
            "payment_method.detached",
            "charge.dispute.created",
            "charge.dispute.closed",
            "charge.dispute.funds_withdrawn",
            "charge.dispute.funds_reinstated",
            "transfer.created",
            "transfer.reversed",
            "transfer.updated",
            "payout.failed",
            "payout.paid",
            "account.application.deauthorized",
            "capability.updated",
            "charge.refund.updated",
            "radar.early_fraud_warning.created"
    );

    private final PaymentService paymentService;
    private final CashCommissionWebhookHandler cashHandler;
    private final ChargebackService chargebackService;
    private final WalletService walletService;

    public PaymentStripeWebhookHandler(PaymentService paymentService,
                                        CashCommissionWebhookHandler cashHandler,
                                        ChargebackService chargebackService,
                                        WalletService walletService) {
        this.paymentService = paymentService;
        this.cashHandler = cashHandler;
        this.chargebackService = chargebackService;
        this.walletService = walletService;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void handle(Event event) {
        switch (event.getType()) {
            case "account.updated"                          -> paymentService.handleAccountUpdated(event);
            case "payment_intent.amount_capturable_updated" -> paymentService.handlePaymentEscrowActive(event);
            case "payment_intent.payment_failed" -> {
                paymentService.handlePaymentFailed(event);
                cashHandler.handlePaymentIntentFailed(event);
            }
            case "charge.refunded"                    -> paymentService.handleChargeRefunded(event);
            case "setup_intent.succeeded"             -> cashHandler.handleSetupIntentSucceeded(event);
            case "payment_intent.succeeded" -> {
                PaymentIntent pi = (PaymentIntent) event.getDataObjectDeserializer()
                    .getObject().orElse(null);
                if (pi != null
                        && pi.getMetadata() != null
                        && "true".equals(pi.getMetadata().get("wallet_topup"))) {
                    UUID userId = UUID.fromString(pi.getMetadata().get("user_id"));
                    BigDecimal amount = BigDecimal.valueOf(pi.getAmount())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    walletService.credit(userId, amount, WalletTransactionType.TOP_UP,
                        pi.getId(), "stripe-" + pi.getId());
                } else {
                    cashHandler.handlePaymentIntentSucceeded(event);
                }
            }
            case "payment_method.detached"            -> cashHandler.handlePaymentMethodDetached(event);
            case "charge.dispute.created"             -> chargebackService.handleDisputeCreated(event);
            case "charge.dispute.closed"              -> chargebackService.handleDisputeClosed(event);
            case "charge.dispute.funds_withdrawn"     -> chargebackService.handleFundsWithdrawn(event);
            case "charge.dispute.funds_reinstated"    -> chargebackService.handleFundsReinstated(event);
            case "payment_intent.canceled"             -> paymentService.handlePaymentIntentCanceled(event);
            case "transfer.created"                    -> paymentService.handleTransferCreated(event);
            case "transfer.reversed"                   -> paymentService.handleTransferReversed(event);
            case "transfer.updated"                    -> paymentService.handleTransferUpdated(event);
            case "payout.failed"                       -> paymentService.handlePayoutFailed(event);
            case "payout.paid"                         -> paymentService.handlePayoutPaid(event);
            case "account.application.deauthorized"   -> paymentService.handleAccountDeauthorized(event);
            case "capability.updated"                 -> paymentService.handleCapabilityUpdated(event);
            case "charge.refund.updated"              -> paymentService.handleRefundUpdated(event);
            case "radar.early_fraud_warning.created"  -> paymentService.handleEarlyFraudWarning(event);
        }
    }
}
