package com.yadony.api.payments.wallet;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class InsufficientWalletBalanceException extends YadonyBusinessException {

    private final BigDecimal availableBalance;

    public InsufficientWalletBalanceException(BigDecimal available, BigDecimal required) {
        super(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "insufficient-wallet-balance",
            "Solde insuffisant",
            String.format("Solde disponible : %.2f € — Montant requis : %.2f €", available, required)
        );
        this.availableBalance = available;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }
}
