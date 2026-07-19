package com.dony.api.payments.wallet;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.money.CountryCurrencies;
import com.dony.api.common.money.CurrencyRegistry;
import com.dony.api.common.money.Money;
import com.dony.api.common.money.MoneyConversion;
import com.dony.api.common.money.PeggedFxRateProvider;
import com.dony.api.payments.wallet.dto.WalletTopupRequest;
import com.dony.api.payments.wallet.dto.WalletTopupResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletTopupOrchestratorTest {

    @Mock private CurrencyRegistry currencyRegistry;
    @Mock private PeggedFxRateProvider peggedFxRateProvider;
    @Mock private WalletTopupRequestRepository topupRequestRepository;
    @Mock private GeniusPayClient geniusPayClient;

    private WalletTopupOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new WalletTopupOrchestrator(
                currencyRegistry, peggedFxRateProvider, topupRequestRepository, geniusPayClient);
    }

    private WalletTopupRequest waveRequest(String amount) {
        WalletTopupRequest r = new WalletTopupRequest();
        r.setAmount(new BigDecimal(amount));
        r.setPaymentMethod("WAVE");
        r.setCountryCode("SN");
        r.setPhoneNumber("+221771234567");
        return r;
    }

    @Test
    void mobileMoneyTopup_freezesAmountAndCallsGeniusPay() {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = waveRequest("10.00");

        Money target = new Money(new BigDecimal("6559.57"), "XOF");
        lenient().when(peggedFxRateProvider.convert(eq(new Money(new BigDecimal("10.00"), "EUR")), eq("XOF")))
                .thenReturn(Optional.of(new MoneyConversion(
                        new Money(new BigDecimal("10.00"), "EUR"), target,
                        new BigDecimal("655.957"), "PEGGED")));
        lenient().when(currencyRegistry.minorUnitOf("XOF")).thenReturn(0);
        lenient().when(currencyRegistry.roundingIncrementOf("XOF")).thenReturn(5);
        when(geniusPayClient.createPayment(anyLong(), eq("XOF"), eq("wave"), eq("+221771234567"), anyString()))
                .thenReturn(new GeniusPayPaymentResult("MTX-TEST", "https://wave.com/pay/xxx"));

        WalletTopupResponse response = orchestrator.initiate(userId, request);

        assertThat(response.getRedirectUrl()).isEqualTo("https://wave.com/pay/xxx");
        ArgumentCaptor<WalletTopupRequestEntity> captor = ArgumentCaptor.forClass(WalletTopupRequestEntity.class);
        verify(topupRequestRepository, times(2)).save(captor.capture());
        WalletTopupRequestEntity saved = captor.getValue();
        assertThat(saved.getCurrency()).isEqualTo("XOF");
        assertThat(saved.getExternalReference()).isEqualTo("MTX-TEST");
        assertThat(saved.getAmountEur()).isEqualByComparingTo("10.00");
    }

    @Test
    void mobileMoneyTopup_persistsEntityBeforeCallingGeniusPay() {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = waveRequest("10.00");
        lenient().when(peggedFxRateProvider.convert(any(), eq("XOF")))
                .thenReturn(Optional.of(new MoneyConversion(
                        new Money(new BigDecimal("10.00"), "EUR"), new Money(new BigDecimal("6559.57"), "XOF"),
                        new BigDecimal("655.957"), "PEGGED")));
        lenient().when(currencyRegistry.minorUnitOf("XOF")).thenReturn(0);
        lenient().when(currencyRegistry.roundingIncrementOf("XOF")).thenReturn(5);
        when(geniusPayClient.createPayment(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DonyBusinessException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "geniuspay-error", "err", "err"));

        assertThatThrownBy(() -> orchestrator.initiate(userId, request))
                .isInstanceOf(DonyBusinessException.class);

        // L'entité PENDING est persistée AVANT l'appel réseau, même si celui-ci échoue ensuite.
        verify(topupRequestRepository, times(1)).save(any());
    }

    @Test
    void mobileMoneyTopup_missingPhoneOrCountry_throws422() {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal("10.00"));
        request.setPaymentMethod("WAVE");
        // countryCode/phoneNumber laissés null

        assertThatThrownBy(() -> orchestrator.initiate(userId, request))
                .isInstanceOf(DonyBusinessException.class);
        verifyNoInteractions(geniusPayClient);
    }

    @Test
    void mobileMoneyTopup_unsupportedCountryProviderCombo_throws422() {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal("10.00"));
        request.setPaymentMethod("MTN_MONEY");
        request.setCountryCode("SN"); // MTN non couvert au Sénégal
        request.setPhoneNumber("+221771234567");

        assertThatThrownBy(() -> orchestrator.initiate(userId, request))
                .isInstanceOf(DonyBusinessException.class);
        verifyNoInteractions(geniusPayClient);
    }

    @Test
    void mobileMoneyTopup_unsupportedCurrencyCountry_throws422() {
        UUID userId = UUID.randomUUID();
        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal("10.00"));
        request.setPaymentMethod("WAVE");
        request.setCountryCode("US"); // hors zone CFA
        request.setPhoneNumber("+15551234567");

        assertThatThrownBy(() -> orchestrator.initiate(userId, request))
                .isInstanceOf(DonyBusinessException.class);
        verifyNoInteractions(geniusPayClient);
    }
}
