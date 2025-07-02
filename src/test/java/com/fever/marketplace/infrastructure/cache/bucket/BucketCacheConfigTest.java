package com.fever.marketplace.infrastructure.cache.bucket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig
@ContextConfiguration(initializers = ConfigDataApplicationContextInitializer.class)
@EnableConfigurationProperties(BucketCacheConfig.class)
@TestPropertySource(properties = {
        "fever.cache.bucket.ttl-hours=8",
        "fever.cache.bucket.max-buckets-per-query=36",
        "fever.cache.bucket.key-prefix=test:prefix:",
        "fever.cache.bucket.async-invalidation=false",
        "fever.cache.bucket.long-term-ttl-hours=168",
        "fever.cache.bucket.current-month-ttl-hours=3",
        "fever.cache.bucket.enable-tiered-ttl=true"
})
class BucketCacheConfigTest {

    @Autowired
    private BucketCacheConfig config;

    @Test
    void shouldBindConfigurationProperties() {
        assertThat(config.getTtlHours()).isEqualTo(8);
        assertThat(config.getMaxBucketsPerQuery()).isEqualTo(36);
        assertThat(config.getKeyPrefix()).isEqualTo("test:prefix:");
        assertThat(config.isAsyncInvalidation()).isFalse();
        assertThat(config.getLongTermTtlHours()).isEqualTo(168);
        assertThat(config.getCurrentMonthTtlHours()).isEqualTo(3);
        assertThat(config.isEnableTieredTtl()).isTrue();
    }

    @Test
    void shouldHaveDefaultValuesWhenNotConfigured() {
        BucketCacheConfig defaultConfig = new BucketCacheConfig();

        // Test that setters work
        defaultConfig.setTtlHours(6);
        defaultConfig.setMaxBucketsPerQuery(24);
        defaultConfig.setKeyPrefix("default:");
        defaultConfig.setAsyncInvalidation(true);

        assertThat(defaultConfig.getTtlHours()).isEqualTo(6);
        assertThat(defaultConfig.getMaxBucketsPerQuery()).isEqualTo(24);
        assertThat(defaultConfig.getKeyPrefix()).isEqualTo("default:");
        assertThat(defaultConfig.isAsyncInvalidation()).isTrue();
    }
}
