package com.dony.api.payments.wallet.dto;

import java.math.BigDecimal;
import java.util.List;

public class WalletBalanceResponse {

    private BigDecimal balance;
    private String currency;
    private List<WalletTransactionDto> transactions;

    public WalletBalanceResponse(BigDecimal balance, String currency, List<WalletTransactionDto> transactions) {
        this.balance = balance;
        this.currency = currency;
        this.transactions = transactions;
    }

    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public List<WalletTransactionDto> getTransactions() { return transactions; }
}
