package com.dony.api.auth;

/**
 * Lifecycle of a Stripe Connect account for a user.
 * Replaces the boolean {@code stripe_onboarded} column.
 */
public enum StripeAccountStatus {

    /** No Stripe account has been created yet. */
    NOT_CREATED,

    /** A Stripe Express account exists but onboarding is not complete
     *  ({@code charges_enabled = false}). */
    PENDING_ONBOARDING,

    /** Onboarding is complete and the account can accept charges
     *  ({@code charges_enabled = true}). */
    ONBOARDING_COMPLETE,

    /** Stripe has rejected the account — the user must contact support. */
    REJECTED,

    /** The account has been administratively disabled. */
    DISABLED
}
