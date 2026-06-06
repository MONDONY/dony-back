package com.dony.api.payments.wallet;

import com.dony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletAccountRepository walletAccountRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final AuditService auditService;

    public WalletService(WalletAccountRepository walletAccountRepository,
                         WalletTransactionRepository walletTransactionRepository,
                         AuditService auditService) {
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.auditService = auditService;
    }

    public WalletAccountEntity getOrCreate(UUID userId) {
        return walletAccountRepository.findByUserId(userId).orElseGet(() -> {
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setUserId(userId);
            return walletAccountRepository.save(wallet);
        });
    }

    public BigDecimal getBalance(UUID userId) {
        return getOrCreate(userId).getBalance();
    }

    public List<WalletTransactionEntity> getTransactions(UUID userId, int page) {
        return walletTransactionRepository
            .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, 50))
            .getContent();
    }

    public void credit(UUID userId, BigDecimal amount, WalletTransactionType type,
                       String paymentRef, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<WalletTransactionEntity> existing =
                walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent credit ignored for key={}", idempotencyKey);
                return;
            }
        }

        WalletAccountEntity wallet = getOrCreate(userId);
        BigDecimal newBalance = wallet.getBalance().add(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
            userId, Map.of("amount", amount.toString(), "paymentRef", String.valueOf(paymentRef)));
    }

    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, BigDecimal amount, WalletTransactionType type, UUID bidId) {
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdForUpdate(userId)
            .orElseGet(() -> {
                WalletAccountEntity w = new WalletAccountEntity();
                w.setUserId(userId);
                return walletAccountRepository.save(w);
            });

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setBidId(bidId);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
            userId, Map.of("amount", amount.toString(), "bidId", String.valueOf(bidId)));
    }

    /**
     * Variante de débit pour les contextes SANS bid (ex. commission d'une
     * négociation) : la référence est stockée dans {@code payment_ref} +
     * {@code idempotency_key}, et non dans la colonne FK {@code bid_id}
     * (qui pointe vers {@code bids}). Idempotent via {@code idempotencyKey}.
     */
    @Transactional(noRollbackFor = InsufficientWalletBalanceException.class)
    public void debit(UUID userId, BigDecimal amount, WalletTransactionType type,
                      String paymentRef, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<WalletTransactionEntity> existing =
                walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent debit ignored for key={}", idempotencyKey);
                return;
            }
        }

        WalletAccountEntity wallet = walletAccountRepository.findByUserIdForUpdate(userId)
            .orElseGet(() -> {
                WalletAccountEntity w = new WalletAccountEntity();
                w.setUserId(userId);
                return walletAccountRepository.save(w);
            });

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientWalletBalanceException(wallet.getBalance(), amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletAccountRepository.save(wallet);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setAmount(amount.negate());
        tx.setBalanceAfter(newBalance);
        tx.setPaymentRef(paymentRef);
        tx.setIdempotencyKey(idempotencyKey);
        walletTransactionRepository.save(tx);

        auditService.log("wallet", wallet.getId(), "WALLET_" + type.name(),
            userId, Map.of("amount", amount.toString(), "paymentRef", String.valueOf(paymentRef)));
    }
}
