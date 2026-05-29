package com.dony.api.payments.mobilemoney;

import com.dony.api.payments.cash.PaymentMethod;

public interface MobileMoneyGateway {
    PaymentMethod supportedProvider();
    MobileMoneyLinkResult generatePaymentLink(MobileMoneyPaymentRequest request);
    boolean verifyWebhookSignature(String rawPayload, String signatureHeader);
    String extractExternalReference(String rawPayload);
    boolean isPaymentConfirmed(String rawPayload);
    String extractFailureReason(String rawPayload);
}
