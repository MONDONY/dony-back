package com.dony.api.config;

import com.dony.api.common.stripe.StripeWebhookProperties;
import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties({StripeConnectProperties.class, StripeWebhookProperties.class})
public class StripeConfig {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${stripe.webhook.payments-secret:}")
    private String paymentsWebhookSecret;

    @Value("${stripe.webhook.kyc-secret:}")
    private String kycWebhookSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    @Bean("stripeWebhookSecret")
    public String stripeWebhookSecret() {
        return webhookSecret;
    }

    @Bean("stripePaymentsWebhookSecret")
    public String stripePaymentsWebhookSecret() {
        return paymentsWebhookSecret;
    }

    @Bean("stripeKycWebhookSecret")
    public String stripeKycWebhookSecret() {
        return kycWebhookSecret;
    }

    @Bean
    @Primary
    public StripeWebhookProperties stripeWebhookProperties(
            @Qualifier("dony.stripe.webhook-com.dony.api.common.stripe.StripeWebhookProperties")
            StripeWebhookProperties props) {
        return props;
    }
}
