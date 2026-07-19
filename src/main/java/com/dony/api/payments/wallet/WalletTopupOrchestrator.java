package com.dony.api.payments.wallet;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.money.CountryCurrencies;
import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.MinorUnits;
import com.dony.api.common.money.Money;
import com.dony.api.common.money.MoneyConversion;
import com.dony.api.common.money.MoneyRounding;
import com.dony.api.common.money.PeggedFxRateProvider;
import com.dony.api.payments.wallet.dto.WalletTopupRequest;
import com.dony.api.payments.wallet.dto.WalletTopupResponse;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class WalletTopupOrchestrator {

    private final CurrencyRegistry currencyRegistry;
    private final PeggedFxRateProvider peggedFxRateProvider;
    private final WalletTopupRequestRepository topupRequestRepository;
    private final GeniusPayClient geniusPayClient;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public WalletTopupOrchestrator(CurrencyRegistry currencyRegistry,
                                   PeggedFxRateProvider peggedFxRateProvider,
                                   WalletTopupRequestRepository topupRequestRepository,
                                   GeniusPayClient geniusPayClient) {
        this.currencyRegistry = currencyRegistry;
        this.peggedFxRateProvider = peggedFxRateProvider;
        this.topupRequestRepository = topupRequestRepository;
        this.geniusPayClient = geniusPayClient;
    }

    public WalletTopupResponse initiate(UUID userId, WalletTopupRequest request) {
        return switch (request.getPaymentMethod()) {
            case "STRIPE" -> initiateStripe(userId, request.getAmount());
            case "WAVE" -> initiateMobileMoney(userId, request, "WAVE");
            case "ORANGE_MONEY" -> initiateMobileMoney(userId, request, "ORANGE_MONEY");
            case "MTN_MONEY" -> initiateMobileMoney(userId, request, "MTN_MONEY");
            default -> throw new IllegalArgumentException(
                "Mode de paiement inconnu : " + request.getPaymentMethod());
        };
    }

    private WalletTopupResponse initiateStripe(UUID userId, BigDecimal amount) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(MinorUnits.toMinorExact(new Money(amount, "EUR"), currencyRegistry))
                .setCurrency("eur")
                .putMetadata("wallet_topup", "true")
                .putMetadata("user_id", userId.toString())
                .build();
            PaymentIntent pi = PaymentIntent.create(params);
            return new WalletTopupResponse(pi.getClientSecret(), null);
        } catch (Exception e) {
            throw new RuntimeException("Erreur Stripe topup", e);
        }
    }

    /**
     * Recharge du wallet interne du voyageur via GeniusPay (mode direct).
     * Ne traite JAMAIS le prix du transport — uniquement l'alimentation du
     * wallet que CashCommissionService débite ensuite pour la commission.
     */
    private WalletTopupResponse initiateMobileMoney(UUID userId, WalletTopupRequest request, String provider) {
        String countryCode = request.getCountryCode();
        String phoneNumber = request.getPhoneNumber();
        if (countryCode == null || phoneNumber == null) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "topup-phone-required", "Phone Required",
                    "Le numéro de téléphone et le pays sont requis pour la recharge mobile money");
        }
        if (!GeniusPayCoverage.supports(countryCode, provider)) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "unsupported-country-provider-combo", "Unsupported Country/Provider",
                    provider + " n'est pas disponible pour le pays " + countryCode);
        }

        BigDecimal amountEur = request.getAmount();
        String walletCurrency = CountryCurrencies.forCountry(countryCode)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "unsupported-topup-country", "Unsupported Country",
                        "Pays non couvert : " + countryCode));

        // Gel du montant local (règle R2) : parité en base + arrondi transactionnel.
        MoneyConversion conv = peggedFxRateProvider.convert(new Money(amountEur, "EUR"), walletCurrency)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "currency-not-convertible", "Currency Not Convertible",
                        "Aucune parité en base pour " + walletCurrency));
        long amountMinor = MoneyRounding.roundTransactionalMinor(
                MinorUnits.toMinor(conv.target(), currencyRegistry),
                currencyRegistry.roundingIncrementOf(walletCurrency));

        WalletTopupRequestEntity entity = new WalletTopupRequestEntity();
        entity.setUserId(userId);
        entity.setProvider(provider);
        entity.setCountryCode(countryCode);
        entity.setPhoneNumber(phoneNumber);
        entity.setAmountEur(amountEur);
        entity.setCurrency(walletCurrency);
        entity.setAmountMinor(amountMinor);
        entity.setFxRate(conv.rate());
        entity.setRateSource(conv.rateSource());
        entity.setStatus("PENDING");
        topupRequestRepository.save(entity);

        // GeniusPay n'accepte que des URLs http(s) (jamais un schéma custom "dony://") —
        // la page de rebond GeniusPayReturnController redirige ensuite vers l'app.
        String successUrl = appBaseUrl + "/api/v1/payments/geniuspay/return?status=success";
        String errorUrl = appBaseUrl + "/api/v1/payments/geniuspay/return?status=error";

        GeniusPayPaymentResult result = geniusPayClient.createPayment(
                amountMinor, walletCurrency, toGeniusPayMethod(provider), phoneNumber,
                "Recharge wallet dony", successUrl, errorUrl);

        entity.setExternalReference(result.reference());
        topupRequestRepository.save(entity);

        return new WalletTopupResponse(null, result.paymentUrl());
    }

    private String toGeniusPayMethod(String provider) {
        return switch (provider) {
            case "WAVE" -> "wave";
            case "ORANGE_MONEY" -> "orange_money";
            case "MTN_MONEY" -> "mtn_money";
            default -> throw new IllegalStateException("Provider inconnu : " + provider);
        };
    }
}
