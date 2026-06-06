package com.dony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.PaymentIntent;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Component;

/**
 * Production {@link StripeGateway}: each method is a direct passthrough to the Stripe SDK
 * static call it replaces, so the runtime behaviour of {@link PaymentService} is identical
 * to calling the statics inline.
 */
@Component
public class StripeGatewayImpl implements StripeGateway {

    @Override
    public Account createAccount(AccountCreateParams params) throws StripeException {
        return Account.create(params);
    }

    @Override
    public Account retrieveAccount(String accountId) throws StripeException {
        return Account.retrieve(accountId);
    }

    @Override
    public AccountLink createAccountLink(AccountLinkCreateParams params) throws StripeException {
        return AccountLink.create(params);
    }

    @Override
    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException {
        return PaymentIntent.create(params);
    }

    @Override
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId);
    }

    @Override
    public PaymentIntent capturePaymentIntent(PaymentIntent paymentIntent) throws StripeException {
        return paymentIntent.capture();
    }
}
