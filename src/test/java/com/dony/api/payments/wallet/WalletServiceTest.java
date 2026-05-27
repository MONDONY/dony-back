package com.dony.api.payments.wallet;

import com.dony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock WalletAccountRepository walletAccountRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock AuditService auditService;

    WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletAccountRepository, walletTransactionRepository, auditService);
    }

    @Test
    void getOrCreate_createsWalletIfNotExists() {
        UUID userId = UUID.randomUUID();
        when(walletAccountRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(walletAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletAccountEntity wallet = walletService.getOrCreate(userId);

        assertThat(wallet.getUserId()).isEqualTo(userId);
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(walletAccountRepository).save(any());
    }

    @Test
    void getOrCreate_returnsExistingWallet() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity existing = new WalletAccountEntity();
        existing.setUserId(userId);
        existing.setBalance(new BigDecimal("42.00"));
        when(walletAccountRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        WalletAccountEntity wallet = walletService.getOrCreate(userId);

        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("42.00"));
        verify(walletAccountRepository, never()).save(any());
    }

    @Test
    void credit_increasesBalanceAndLogsTransaction() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("10.00"));
        when(walletAccountRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletTransactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.credit(userId, new BigDecimal("50.00"), WalletTransactionType.TOP_UP, "pi_123", "idem-001");

        ArgumentCaptor<WalletAccountEntity> walletCaptor = ArgumentCaptor.forClass(WalletAccountEntity.class);
        verify(walletAccountRepository).save(walletCaptor.capture());
        assertThat(walletCaptor.getValue().getBalance()).isEqualByComparingTo(new BigDecimal("60.00"));

        ArgumentCaptor<WalletTransactionEntity> txCaptor = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(txCaptor.getValue().getBalanceAfter()).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void credit_idempotent_doesNothingIfKeyAlreadyProcessed() {
        UUID userId = UUID.randomUUID();
        WalletTransactionEntity existing = new WalletTransactionEntity();
        when(walletTransactionRepository.findByIdempotencyKey("idem-001")).thenReturn(Optional.of(existing));

        walletService.credit(userId, new BigDecimal("50.00"), WalletTransactionType.TOP_UP, "pi_123", "idem-001");

        verify(walletAccountRepository, never()).save(any());
    }

    @Test
    void debit_sufficientBalance_decreasesBalance() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("100.00"));
        when(walletAccountRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(walletAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit(userId, new BigDecimal("30.00"), WalletTransactionType.BID_PAYMENT, null);

        ArgumentCaptor<WalletAccountEntity> captor = ArgumentCaptor.forClass(WalletAccountEntity.class);
        verify(walletAccountRepository).save(captor.capture());
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    void debit_insufficientBalance_throwsException() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("10.00"));
        when(walletAccountRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));

        Throwable thrown = catchThrowable(() ->
            walletService.debit(userId, new BigDecimal("50.00"), WalletTransactionType.BID_PAYMENT, null)
        );

        assertThat(thrown).isInstanceOf(InsufficientWalletBalanceException.class);
        verify(walletAccountRepository, never()).save(any());
    }
}
