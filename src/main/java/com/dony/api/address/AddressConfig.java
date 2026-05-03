package com.dony.api.address;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
@EnableConfigurationProperties(GooglePlacesProperties.class)
public class AddressConfig {

    @Bean("placesRestTemplate")
    public RestTemplate placesRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(5_000);
        return new RestTemplate(factory);
    }

    @Bean("addressRateLimitCache")
    public Cache<String, AtomicInteger> addressRateLimitCache() {
        return Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();
    }

    @Bean("addressDailyQuotaCache")
    public Cache<String, AtomicLong> addressDailyQuotaCache() {
        return Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(25, TimeUnit.HOURS)
            .build();
    }
}
