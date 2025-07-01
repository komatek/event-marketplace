package com.fever.marketplace.infrastructure.cache.bucket;

import com.fever.marketplace.domain.model.Event;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced bucket operations with intelligent TTL management
 * Optimized for monthly bucket strategy with tiered caching
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
     * Get events from a specific monthly bucket
     */
    public List<Event> getBucketEvents(LocalDate bucketKey) {
        try {
            String cacheKey = generateBucketKey(bucketKey);
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);

            if (cachedJson != null && !cachedJson.isEmpty()) {
                List<Event> events = objectMapper.readValue(cachedJson, new TypeReference<>() {});
                logger.debug("Cache hit for bucket {}: {} events", bucketKey, events.size());
                return events;
            }

            return null; // Cache miss

        } catch (Exception e) {
            logger.warn("Failed to get bucket {}: {}", bucketKey, e.getMessage());
            return null;
        }
    }

    /**
     * Store events in a specific monthly bucket with intelligent TTL
     */
    public void putBucketEvents(LocalDate bucketKey, List<Event> events) {
        try {
            String cacheKey = generateBucketKey(bucketKey);
            String json = objectMapper.writeValueAsString(events);

            // Calculate appropriate TTL based on bucket age
            int ttlHours = calculateTtlForBucket(bucketKey);

            redisTemplate.opsForValue().set(cacheKey, json, ttlHours, TimeUnit.HOURS);

            logger.debug("Cached monthly bucket {} with {} events (TTL: {}h)",
                    YearMonth.from(bucketKey), events.size(), ttlHours);

        } catch (Exception e) {
            logger.warn("Failed to cache bucket {}: {}", bucketKey, e.getMessage());
        }
    }

    /**
     * Remove a specific monthly bucket from cache
     */
    public boolean invalidateBucket(LocalDate bucketKey) {
        try {
            String cacheKey = generateBucketKey(bucketKey);
            Boolean deleted = redisTemplate.delete(cacheKey);

            if (Boolean.TRUE.equals(deleted)) {
                logger.debug("Invalidated monthly bucket: {}", YearMonth.from(bucketKey));
                return true;
            }

            return false;

        } catch (Exception e) {
            logger.warn("Failed to invalidate bucket {}: {}", bucketKey, e.getMessage());
            return false;
        }
    }

    /**
     * Generate cache key for monthly bucket
     */
    private String generateBucketKey(LocalDate bucketKey) {
        // Ensure we're using first day of month for consistency
        YearMonth month = YearMonth.from(bucketKey);
        return config.getKeyPrefix() + month.toString(); // e.g., "fever:events:month:2024-12"
    }

    /**
     * Calculate TTL based on bucket age using tiered strategy
     */
    private int calculateTtlForBucket(LocalDate bucketKey) {
        if (!config.isEnableTieredTtl()) {
            return config.getTtlHours();
        }

        YearMonth bucketMonth = YearMonth.from(bucketKey);
        YearMonth currentMonth = YearMonth.now();
        long monthsAgo = ChronoUnit.MONTHS.between(bucketMonth, currentMonth);

        if (monthsAgo == 0) {
            // Current month: shorter TTL (more dynamic, frequently changing)
            return config.getCurrentMonthTtlHours();
        } else if (monthsAgo <= 3) {
            // Recent months (1-3 months ago): normal TTL
            return config.getTtlHours();
        } else {
            // Older months (> 3 months ago): longer TTL (less likely to change)
            return config.getLongTermTtlHours();
        }
    }
}
