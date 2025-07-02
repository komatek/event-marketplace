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

    private int ttlHours;
    private int maxBucketsPerQuery;
    private String keyPrefix;
    private boolean asyncInvalidation;

    private int longTermTtlHours;
    private int currentMonthTtlHours;
    private boolean enableTieredTtl;

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

}
