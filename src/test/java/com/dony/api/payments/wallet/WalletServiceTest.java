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

    @Test
    void getBalance_returnsExistingWalletBalance() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("33.50"));
        when(walletAccountRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        assertThat(walletService.getBalance(userId)).isEqualByComparingTo(new BigDecimal("33.50"));
    }

    @Test
    void getTransactions_returnsHistoryContent() {
        UUID userId = UUID.randomUUID();
        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setType(WalletTransactionType.REFUND);
        tx.setAmount(new BigDecimal("5.00"));
        when(walletTransactionRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(tx)));

        var history = walletService.getTransactions(userId, 0);

        assertThat(history).containsExactly(tx);
    }

    @Test
    void debit_createsWalletWhenMissing_thenThrowsInsufficient() {
        // Couvre le orElseGet de debit() : aucun wallet → création (solde 0) → solde insuffisant.
        UUID userId = UUID.randomUUID();
        when(walletAccountRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());
        when(walletAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Throwable thrown = catchThrowable(() ->
            walletService.debit(userId, new BigDecimal("12.00"),
                    WalletTransactionType.COMMISSION_DEDUCTED, UUID.randomUUID())
        );

        assertThat(thrown).isInstanceOf(InsufficientWalletBalanceException.class);
        // Le wallet nouvellement créé a bien été persisté (orElseGet) avant le contrôle de solde.
        verify(walletAccountRepository).save(any());
    }

    @Test
    void debitWithPaymentRef_setsPaymentRefAndIdempotencyKey_noBidId() {
        // Overload sans bid (commission négociation) : réf dans payment_ref + idempotency_key,
        // bid_id laissé null (pas de FK vers bids).
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("100.00"));
        when(walletTransactionRepository.findByIdempotencyKey("nego_commission_wallet_t1"))
                .thenReturn(Optional.empty());
        when(walletAccountRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(walletAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit(userId, new BigDecimal("12.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "nego_commission_wallet_t1");

        ArgumentCaptor<WalletAccountEntity> wcap = ArgumentCaptor.forClass(WalletAccountEntity.class);
        verify(walletAccountRepository).save(wcap.capture());
        assertThat(wcap.getValue().getBalance()).isEqualByComparingTo(new BigDecimal("88.00"));

        ArgumentCaptor<WalletTransactionEntity> tcap = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).save(tcap.capture());
        WalletTransactionEntity tx = tcap.getValue();
        assertThat(tx.getBidId()).isNull();
        assertThat(tx.getPaymentRef()).isEqualTo("thread-1");
        assertThat(tx.getIdempotencyKey()).isEqualTo("nego_commission_wallet_t1");
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("-12.00"));
    }

    @Test
    void debitWithPaymentRef_idempotentSkipWhenKeyExists() {
        UUID userId = UUID.randomUUID();
        WalletTransactionEntity existing = new WalletTransactionEntity();
        when(walletTransactionRepository.findByIdempotencyKey("dup-key"))
                .thenReturn(Optional.of(existing));

        walletService.debit(userId, new BigDecimal("12.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "dup-key");

        // Aucun débit : ni lock wallet, ni save compte, ni save transaction.
        verify(walletAccountRepository, never()).findByUserIdForUpdate(any());
        verify(walletAccountRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    void debitWithPaymentRef_insufficientBalance_throws() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("5.00"));
        when(walletTransactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletAccountRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));

        Throwable thrown = catchThrowable(() ->
            walletService.debit(userId, new BigDecimal("12.00"),
                    WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "k1")
        );

        assertThat(thrown).isInstanceOf(InsufficientWalletBalanceException.class);
        verify(walletTransactionRepository, never()).save(any());
    }
}
