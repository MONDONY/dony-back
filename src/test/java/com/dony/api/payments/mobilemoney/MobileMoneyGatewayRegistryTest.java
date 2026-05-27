package com.dony.api.payments.mobilemoney;

import com.dony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MobileMoneyGatewayRegistryTest {

    @Test
    void getGateway_wave_returnsWaveGateway() {
        MobileMoneyGateway waveGw = new WaveGateway(stubProps());
        MobileMoneyGateway omGw   = new OrangeMoneyGateway(stubProps());
        MobileMoneyGatewayRegistry registry = new MobileMoneyGatewayRegistry(List.of(waveGw, omGw));

        assertThat(registry.getGateway(PaymentMethod.WAVE)).isInstanceOf(WaveGateway.class);
        assertThat(registry.getGateway(PaymentMethod.ORANGE_MONEY)).isInstanceOf(OrangeMoneyGateway.class);
    }

    @Test
    void getGateway_unknownProvider_throwsIllegalArgument() {
        MobileMoneyGatewayRegistry registry = new MobileMoneyGatewayRegistry(List.of());
        assertThatThrownBy(() -> registry.getGateway(PaymentMethod.STRIPE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MobileMoneyProperties stubProps() {
        return new MobileMoneyProperties(
                new MobileMoneyProperties.ProviderConfig("key", "https://stub.example.com", "secret"),
                new MobileMoneyProperties.ProviderConfig("key", "https://stub.example.com", "secret"),
                30
        );
    }
}
