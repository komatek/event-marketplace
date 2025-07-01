package com.fever.marketplace.infrastructure.cache.bucket;

import com.fever.marketplace.domain.model.Event;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles individual bucket operations
 * Separated from main cache strategy for better testability
 */
@Component
public class BucketOperations {

    private static final Logger logger = LoggerFactory.getLogger(BucketOperations.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final BucketCacheConfig config;

    public BucketOperations(RedisTemplate<String, String> redisTemplate,
                            ObjectMapper objectMapper,
                            BucketCacheConfig config) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * Get events from a specific bucket
     */
    public List<Event> getBucketEvents(LocalDate bucket) {
        try {
            String cacheKey = generateBucketKey(bucket);
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);

            if (cachedJson != null && !cachedJson.isEmpty()) {
                List<Event> events = objectMapper.readValue(cachedJson, new TypeReference<List<Event>>() {});
                logger.debug("Cache hit for bucket {}: {} events", bucket, events.size());
                return events;
            }

            return null; // Cache miss

        } catch (Exception e) {
            logger.warn("Failed to get bucket {}: {}", bucket, e.getMessage());
            return null;
        }
    }

    /**
     * Store events in a specific bucket
     */
    public void putBucketEvents(LocalDate bucket, List<Event> events) {
        try {
            String cacheKey = generateBucketKey(bucket);
            String json = objectMapper.writeValueAsString(events);

            redisTemplate.opsForValue().set(cacheKey, json, config.ttlHours(), TimeUnit.HOURS);

            logger.debug("Cached bucket {} with {} events (TTL: {}h)",
                    bucket, events.size(), config.ttlHours());

        } catch (Exception e) {
            logger.warn("Failed to cache bucket {}: {}", bucket, e.getMessage());
        }
    }

    /**
     * Remove a specific bucket from cache
     */
    public boolean invalidateBucket(LocalDate bucket) {
        try {
            String cacheKey = generateBucketKey(bucket);
            Boolean deleted = redisTemplate.delete(cacheKey);

            if (Boolean.TRUE.equals(deleted)) {
                logger.debug("Invalidated bucket: {}", bucket);
                return true;
            }

            return false;

        } catch (Exception e) {
            logger.warn("Failed to invalidate bucket {}: {}", bucket, e.getMessage());
            return false;
        }
    }

    /**
     * Generate cache key for bucket
     */
    private String generateBucketKey(LocalDate bucket) {
        return config.keyPrefix() + bucket.toString();
    }
}
