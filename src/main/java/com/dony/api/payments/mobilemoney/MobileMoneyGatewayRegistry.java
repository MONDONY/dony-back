package com.dony.api.payments.mobilemoney;

import com.dony.api.payments.cash.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MobileMoneyGatewayRegistry {

    private final Map<PaymentMethod, MobileMoneyGateway> gateways;

    public MobileMoneyGatewayRegistry(List<MobileMoneyGateway> gatewayList) {
        this.gateways = gatewayList.stream()
                .collect(Collectors.toMap(MobileMoneyGateway::supportedProvider, Function.identity()));
    }

    public MobileMoneyGateway getGateway(PaymentMethod provider) {
        MobileMoneyGateway gw = gateways.get(provider);
        if (gw == null) {
            throw new IllegalArgumentException("No Mobile Money gateway for provider: " + provider);
        }
        return gw;
    }

    public boolean isMobileMoneyProvider(PaymentMethod pm) {
        return gateways.containsKey(pm);
    }
}
