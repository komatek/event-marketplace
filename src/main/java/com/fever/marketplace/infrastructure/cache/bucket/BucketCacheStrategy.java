package com.fever.marketplace.infrastructure.cache.bucket;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.cache.CacheStats;
import com.fever.marketplace.infrastructure.cache.EventCacheStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bucket-based caching strategy implementation
 * Uses daily buckets for optimal cache hit ratio
 */
@Component
public class BucketCacheStrategy implements EventCacheStrategy {

    private static final Logger logger = LoggerFactory.getLogger(BucketCacheStrategy.class);

    private final BucketOperations bucketOps;
    private final BucketRangeCalculator rangeCalculator;
    private final BucketCacheConfig config;
    private final RedisTemplate<String, String> redisTemplate;

    // Cache statistics
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    public BucketCacheStrategy(BucketOperations bucketOps,
                               BucketRangeCalculator rangeCalculator,
                               BucketCacheConfig config,
                               RedisTemplate<String, String> redisTemplate) {
        this.bucketOps = bucketOps;
        this.rangeCalculator = rangeCalculator;
        this.config = config;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<List<Event>> getEvents(LocalDateTime startsAt, LocalDateTime endsAt) {
        try {
            List<LocalDate> buckets = rangeCalculator.generateDailyBuckets(
                    startsAt.toLocalDate(), endsAt.toLocalDate());

            if (buckets.size() > config.maxBucketsPerQuery()) {
                logger.warn("Query range too large: {} buckets", buckets.size());
                return Optional.empty(); // Fall back to database
            }

            Map<LocalDate, List<Event>> bucketResults = new HashMap<>();
            boolean hasAllBuckets = true;

            // Try to fetch all buckets from cache
            for (LocalDate bucket : buckets) {
                List<Event> bucketEvents = bucketOps.getBucketEvents(bucket);
                if (bucketEvents != null) {
                    bucketResults.put(bucket, bucketEvents);
                    hits.incrementAndGet();
                } else {
                    hasAllBuckets = false;
                    misses.incrementAndGet();
                    break; // If any bucket is missing, fetch from database
                }
            }

            if (!hasAllBuckets) {
                return Optional.empty(); // Cache miss
            }

            // Merge and filter results
            List<Event> allEvents = bucketResults.values().stream()
                    .flatMap(List::stream)
                    .filter(event -> isEventInRange(event, startsAt, endsAt))
                    .sorted(this::compareEvents)
                    .toList();

            return Optional.of(allEvents);

        } catch (Exception e) {
            logger.error("Cache get error: {}", e.getMessage());
            errors.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void putEvents(LocalDateTime startsAt, LocalDateTime endsAt, List<Event> events) {
        try {
            // Group events by date bucket
            Map<LocalDate, List<Event>> eventsByBucket = groupEventsByBucket(events);

            // Store each bucket
            for (Map.Entry<LocalDate, List<Event>> entry : eventsByBucket.entrySet()) {
                bucketOps.putBucketEvents(entry.getKey(), entry.getValue());
            }

        } catch (Exception e) {
            logger.error("Cache put error: {}", e.getMessage());
            errors.incrementAndGet();
        }
    }

    @Override
    public void invalidateAffectedEntries(List<Event> newEvents) {
        if (config.asyncInvalidation()) {
            invalidateAsync(newEvents);
        } else {
            invalidateSync(newEvents);
        }
    }

    @Async("asyncExecutor")
    public CompletableFuture<Void> invalidateAsync(List<Event> newEvents) {
        invalidateSync(newEvents);
        return CompletableFuture.completedFuture(null);
    }

    private void invalidateSync(List<Event> newEvents) {
        try {
            Set<LocalDate> affectedBuckets = rangeCalculator.calculateAffectedBuckets(newEvents);
            int invalidatedCount = 0;

            for (LocalDate bucket : affectedBuckets) {
                if (bucketOps.invalidateBucket(bucket)) {
                    invalidatedCount++;
                }
            }

            invalidations.addAndGet(invalidatedCount);
            logger.info("Invalidated {} buckets for {} new events",
                    invalidatedCount, newEvents.size());

        } catch (Exception e) {
            logger.error("Invalidation error: {}", e.getMessage());
            errors.incrementAndGet();
        }
    }

    @Override
    public CacheStats getStats() {
        try {
            Set<String> bucketKeys = redisTemplate.keys(config.keyPrefix() + "*");
            int activeEntries = bucketKeys != null ? bucketKeys.size() : 0;

            return new CacheStats(
                    hits.get(),
                    misses.get(),
                    errors.get(),
                    invalidations.get(),
                    activeEntries
            );
        } catch (Exception e) {
            logger.warn("Failed to get cache stats: {}", e.getMessage());
            return new CacheStats(hits.get(), misses.get(), errors.get(), invalidations.get(), 0);
        }
    }

    // ===== Private Helper Methods =====

    private Map<LocalDate, List<Event>> groupEventsByBucket(List<Event> events) {
        Map<LocalDate, List<Event>> buckets = new HashMap<>();

        for (Event event : events) {
            LocalDate current = event.startDate();
            while (!current.isAfter(event.endDate())) {
                buckets.computeIfAbsent(current, k -> new ArrayList<>()).add(event);
                current = current.plusDays(1);
            }
        }

        return buckets;
    }

    private boolean isEventInRange(Event event, LocalDateTime startsAt, LocalDateTime endsAt) {
        LocalDateTime eventStart = LocalDateTime.of(event.startDate(), event.startTime());
        LocalDateTime eventEnd = LocalDateTime.of(event.endDate(), event.endTime());

        return !eventStart.isAfter(endsAt) && !eventEnd.isBefore(startsAt);
    }

    private int compareEvents(Event e1, Event e2) {
        int dateCompare = e1.startDate().compareTo(e2.startDate());
        return dateCompare != 0 ? dateCompare : e1.startTime().compareTo(e2.startTime());
    }
}
