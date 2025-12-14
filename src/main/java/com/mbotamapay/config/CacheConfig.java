package com.mbotamapay.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Cache Configuration
 * Configures Caffeine as the in-memory cache provider
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Default cache manager with Caffeine
     * Different caches can be configured with different TTLs
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(500)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats());

        // Register specific cache names
        cacheManager.setCacheNames(java.util.List.of(
                "users",
                "userByPhone",
                "gatewayStatus",
                "kycLimits"));

        return cacheManager;
    }

    /**
     * Short-lived cache for frequently accessed but volatile data
     */
    @Bean
    public Caffeine<Object, Object> shortLivedCacheBuilder() {
        return Caffeine.newBuilder()
                .initialCapacity(50)
                .maximumSize(200)
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .recordStats();
    }

    /**
     * Long-lived cache for stable data like configuration
     */
    @Bean
    public Caffeine<Object, Object> longLivedCacheBuilder() {
        return Caffeine.newBuilder()
                .initialCapacity(20)
                .maximumSize(100)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats();
    }
}
