package com.fever.marketplace.infrastructure.cache.bucket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for bucket caching strategy`
 */
@Component
@ConfigurationProperties(prefix = "fever.cache.bucket")
public record BucketCacheConfig(
        int ttlHours,
        int maxBucketsPerQuery,
        String keyPrefix,
        boolean asyncInvalidation
) {
    public BucketCacheConfig() {
        this(6, 180, "fever:events:bucket:", true);
    }
}
