package com.dony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.PaymentIntent;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;

/**
 * Thin seam over the Stripe SDK's static entry points so the surrounding business logic
 * in {@link PaymentService} can be exercised without a live Stripe connection (the e2e
 * profile injects a stub implementation). Production uses {@link StripeGatewayImpl}, whose
 * methods are 1:1 wrappers over the SDK statics — behaviour is unchanged.
 */
public interface StripeGateway {

    Account createAccount(AccountCreateParams params) throws StripeException;

    Account retrieveAccount(String accountId) throws StripeException;

    AccountLink createAccountLink(AccountLinkCreateParams params) throws StripeException;

    PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException;

    PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException;

    PaymentIntent capturePaymentIntent(PaymentIntent paymentIntent) throws StripeException;
}
