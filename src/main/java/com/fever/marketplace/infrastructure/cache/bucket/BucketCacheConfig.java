package com.fever.marketplace.infrastructure.cache.bucket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for monthly bucket caching strategy
 * Optimized for scalability with bounded memory usage
 */
@Component
@ConfigurationProperties(prefix = "fever.cache.bucket")
public class BucketCacheConfig {

    // Monthly bucket strategy: max 24 months = 24 cache keys
    private int ttlHours = 12; // 12 hours TTL for active months
    private int maxBucketsPerQuery = 24; // Max 2 years of data per query
    private String keyPrefix = "fever:events:month:"; // Monthly buckets
    private boolean asyncInvalidation = true; // Non-blocking cache updates

    // Advanced configuration for performance tuning
    private int longTermTtlHours = 168; // 1 week TTL for older months (> 6 months old)
    private int currentMonthTtlHours = 2; // Shorter TTL for current month (more dynamic)
    private boolean enableTieredTtl = true; // Different TTL based on month age

    // Getters and setters
    public int getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(int ttlHours) {
        this.ttlHours = ttlHours;
    }

    public int getMaxBucketsPerQuery() {
        return maxBucketsPerQuery;
    }

    public void setMaxBucketsPerQuery(int maxBucketsPerQuery) {
        this.maxBucketsPerQuery = maxBucketsPerQuery;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public boolean isAsyncInvalidation() {
        return asyncInvalidation;
    }

    public void setAsyncInvalidation(boolean asyncInvalidation) {
        this.asyncInvalidation = asyncInvalidation;
    }

    public int getLongTermTtlHours() {
        return longTermTtlHours;
    }

    public void setLongTermTtlHours(int longTermTtlHours) {
        this.longTermTtlHours = longTermTtlHours;
    }

    public int getCurrentMonthTtlHours() {
        return currentMonthTtlHours;
    }

    public void setCurrentMonthTtlHours(int currentMonthTtlHours) {
        this.currentMonthTtlHours = currentMonthTtlHours;
    }

    public boolean isEnableTieredTtl() {
        return enableTieredTtl;
    }

    public void setEnableTieredTtl(boolean enableTieredTtl) {
        this.enableTieredTtl = enableTieredTtl;
    }

    // For backward compatibility with record-style access
    public int ttlHours() {
        return ttlHours;
    }

    public int maxBucketsPerQuery() {
        return maxBucketsPerQuery;
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    public boolean asyncInvalidation() {
        return asyncInvalidation;
    }

    public int longTermTtlHours() {
        return longTermTtlHours;
    }

    public int currentMonthTtlHours() {
        return currentMonthTtlHours;
    }

    public boolean enableTieredTtl() {
        return enableTieredTtl;
    }
}
