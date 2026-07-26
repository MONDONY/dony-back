package com.dony.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DonyConfigPropertiesReimbursementTest {

    private DonyConfigProperties bind(MockEnvironment env) {
        var sources = ConfigurationPropertySources.from(env.getPropertySources());
        return new Binder(sources)
                .bind("dony", DonyConfigProperties.class)
                .orElse(new DonyConfigProperties(null, null, null, null));
    }

    @Test
    void bindsConfiguredReimbursementCap() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("dony.reimbursement.max-amount-eur", "75");
        assertThat(bind(env).reimbursement().maxAmountEur())
                .isEqualByComparingTo(new BigDecimal("75"));
    }

    @Test
    void defaultsToFiftyWhenAbsent() {
        DonyConfigProperties props = bind(new MockEnvironment());
        assertThat(props.reimbursement().maxAmountEur())
                .isEqualByComparingTo(new BigDecimal("50"));
    }
}
