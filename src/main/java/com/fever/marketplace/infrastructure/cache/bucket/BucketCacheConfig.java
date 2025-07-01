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
    private int ttlHours; // 12 hours TTL for active months
    private int maxBucketsPerQuery; // Max 2 years of data per query
    private String keyPrefix = "fever:events:month:"; // Monthly buckets
    private boolean asyncInvalidation = true; // Non-blocking cache updates

    private int longTermTtlHours = 168; // 1 week TTL for older months (> 6 months old)
    private int currentMonthTtlHours = 2; // Shorter TTL for current month (more dynamic)
    private boolean enableTieredTtl = true; // Different TTL based on month age


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
