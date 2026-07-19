package com.dony.api.payments.wallet;

import com.dony.api.payments.wallet.dto.WalletTopupRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WalletTopupRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private WalletTopupRequest request(String amount) {
        WalletTopupRequest r = new WalletTopupRequest();
        r.setAmount(new BigDecimal(amount));
        r.setPaymentMethod("STRIPE");
        return r;
    }

    @Test void twoDecimalsAccepted()   { assertThat(validator.validate(request("10.00"))).isEmpty(); }
    @Test void threeDecimalsRejected() { assertThat(validator.validate(request("10.005"))).isNotEmpty(); }
    @Test void integerAccepted()       { assertThat(validator.validate(request("10"))).isEmpty(); }
}
