package com.dony.api.payments.wallet.dto;

public class WalletTopupResponse {

    private String clientSecret;   // set if STRIPE
    private String redirectUrl;    // set if WAVE or ORANGE_MONEY

    public WalletTopupResponse(String clientSecret, String redirectUrl) {
        this.clientSecret = clientSecret;
        this.redirectUrl = redirectUrl;
    }

    public String getClientSecret() { return clientSecret; }
    public String getRedirectUrl() { return redirectUrl; }
}
