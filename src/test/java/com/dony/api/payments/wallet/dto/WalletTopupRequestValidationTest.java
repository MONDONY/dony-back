package com.dony.api.payments.wallet.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WalletTopupRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void mobileMoneyWithValidCountryAndPhoneIsAccepted() {
        WalletTopupRequest r = new WalletTopupRequest();
        r.setAmount(new BigDecimal("10.00"));
        r.setPaymentMethod("WAVE");
        r.setCountryCode("SN");
        r.setPhoneNumber("+221771234567");
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void invalidPhoneFormatIsRejected() {
        WalletTopupRequest r = new WalletTopupRequest();
        r.setAmount(new BigDecimal("10.00"));
        r.setPaymentMethod("WAVE");
        r.setCountryCode("SN");
        r.setPhoneNumber("not-a-phone");
        assertThat(validator.validate(r)).isNotEmpty();
    }

    @Test
    void stripeWithoutCountryOrPhoneIsAccepted() {
        WalletTopupRequest r = new WalletTopupRequest();
        r.setAmount(new BigDecimal("10.00"));
        r.setPaymentMethod("STRIPE");
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void mtnMoneyIsAcceptedAsPaymentMethodValue() {
        WalletTopupRequest r = new WalletTopupRequest();
        r.setAmount(new BigDecimal("10.00"));
        r.setPaymentMethod("MTN_MONEY");
        r.setCountryCode("CI");
        r.setPhoneNumber("+2250701234567");
        assertThat(validator.validate(r)).isEmpty();
    }
}
