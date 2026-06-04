package com.dony.api.payments.cash;

import com.dony.api.payments.wallet.WalletService;
import com.dony.api.requests.CashGatePort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class CashGateAdapter implements CashGatePort {

    private final WalletService walletService;

    public CashGateAdapter(WalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public boolean hasSufficientFunds(UUID travelerId, BigDecimal commissionAmount) {
        BigDecimal balance = walletService.getBalance(travelerId);
        return balance.compareTo(commissionAmount) >= 0;
    }
}
