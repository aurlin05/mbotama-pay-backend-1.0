package com.mbotamapay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token Blacklist Service
 * Manages invalidated JWT tokens to prevent their reuse after logout
 */
@Service
@Slf4j
public class TokenBlacklistService {

    // Map of token hash -> expiration timestamp
    private final Map<String, Instant> blacklistedTokens = new ConcurrentHashMap<>();

    /**
     * Add a token to the blacklist
     * 
     * @param token          The JWT token to blacklist
     * @param expirationTime When the token would normally expire
     */
    public void blacklistToken(String token, Instant expirationTime) {
        if (token == null || token.isEmpty()) {
            return;
        }

        // Store hash of token instead of full token for security
        String tokenHash = hashToken(token);
        blacklistedTokens.put(tokenHash, expirationTime);
        log.debug("Token blacklisted. Total blacklisted tokens: {}", blacklistedTokens.size());
    }

    /**
     * Check if a token is blacklisted
     * 
     * @param token The JWT token to check
     * @return true if the token is blacklisted
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        String tokenHash = hashToken(token);
        return blacklistedTokens.containsKey(tokenHash);
    }

    /**
     * Remove a specific token from the blacklist (if needed)
     */
    public void removeFromBlacklist(String token) {
        if (token != null && !token.isEmpty()) {
            blacklistedTokens.remove(hashToken(token));
        }
    }

    /**
     * Clean up expired tokens from the blacklist
     * Runs every hour to free up memory
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();
        int beforeSize = blacklistedTokens.size();

        blacklistedTokens.entrySet().removeIf(entry -> entry.getValue().isBefore(now));

        int removed = beforeSize - blacklistedTokens.size();
        if (removed > 0) {
            log.info("Cleaned up {} expired tokens from blacklist. Remaining: {}", removed, blacklistedTokens.size());
        }
    }

    /**
     * Get the current size of the blacklist
     */
    public int getBlacklistSize() {
        return blacklistedTokens.size();
    }

    /**
     * Clear all blacklisted tokens (admin function)
     */
    public void clearBlacklist() {
        blacklistedTokens.clear();
        log.info("Token blacklist cleared");
    }

    /**
     * Hash the token for secure storage
     * We don't need to store the full token, just enough to identify it
     */
    private String hashToken(String token) {
        // Use last 32 characters of the token signature as identifier
        // This is unique enough for our purposes and doesn't store the full token
        if (token.length() > 32) {
            return token.substring(token.length() - 32);
        }
        return token;
    }
}
