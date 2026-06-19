package com.dony.api.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Default spec: 5-minute TTL, max 500 entries
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(5, TimeUnit.MINUTES));

        // Per-cache overrides
        // adminAuthz: short-lived (30 s), small (200 entries) — auth hot path
        manager.registerCustomCache("adminAuthz",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .build());

        // Standard caches that use the default spec
        manager.setCacheNames(java.util.List.of(
                "announcements-search",
                "estimation-corridor",
                "trips-summary"
        ));

        return manager;
    }
}
