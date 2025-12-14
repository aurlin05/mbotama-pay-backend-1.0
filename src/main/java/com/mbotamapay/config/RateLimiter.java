package com.mbotamapay.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate Limiter Configuration using Bucket4j
 * Provides rate limiting per IP address for different endpoints
 */
@Component
public class RateLimiter {

    // Buckets per IP address for different endpoints
    private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> otpBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> registerBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> generalBuckets = new ConcurrentHashMap<>();

    /**
     * Get or create rate limit bucket for login attempts
     * Limit: 5 attempts per minute
     */
    public Bucket getLoginBucket(String ip) {
        return loginBuckets.computeIfAbsent(ip, k -> createBucket(5, Duration.ofMinutes(1)));
    }

    /**
     * Get or create rate limit bucket for OTP verification
     * Limit: 3 attempts per minute
     */
    public Bucket getOtpBucket(String ip) {
        return otpBuckets.computeIfAbsent(ip, k -> createBucket(3, Duration.ofMinutes(1)));
    }

    /**
     * Get or create rate limit bucket for registration
     * Limit: 3 attempts per 5 minutes
     */
    public Bucket getRegisterBucket(String ip) {
        return registerBuckets.computeIfAbsent(ip, k -> createBucket(3, Duration.ofMinutes(5)));
    }

    /**
     * Get or create rate limit bucket for general API calls
     * Limit: 100 requests per minute
     */
    public Bucket getGeneralBucket(String ip) {
        return generalBuckets.computeIfAbsent(ip, k -> createBucket(100, Duration.ofMinutes(1)));
    }

    /**
     * Create a new bucket with the specified capacity and refill duration
     */
    private Bucket createBucket(int capacity, Duration refillDuration) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.intervally(capacity, refillDuration));
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Clear all buckets (useful for testing or admin reset)
     */
    public void clearAllBuckets() {
        loginBuckets.clear();
        otpBuckets.clear();
        registerBuckets.clear();
        generalBuckets.clear();
    }

    /**
     * Clear buckets for a specific IP
     */
    public void clearBucketsForIp(String ip) {
        loginBuckets.remove(ip);
        otpBuckets.remove(ip);
        registerBuckets.remove(ip);
        generalBuckets.remove(ip);
    }
}
