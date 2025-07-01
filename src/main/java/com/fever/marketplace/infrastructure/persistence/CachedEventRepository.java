package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import com.fever.marketplace.infrastructure.cache.EventCacheStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Cached implementation of EventRepository with async cache population
 * Ensures UX is never impacted by cache operations
 */
@Repository
@Primary
public class CachedEventRepository implements EventRepository {

    private static final Logger logger = LoggerFactory.getLogger(CachedEventRepository.class);

    private final EventRepository databaseRepository;
    private final EventCacheStrategy cacheStrategy;

    public CachedEventRepository(EventRepository databaseRepository,
                                 EventCacheStrategy cacheStrategy) {
        this.databaseRepository = databaseRepository;
        this.cacheStrategy = cacheStrategy;
    }

    @Override
    public List<Event> findByDateRange(LocalDateTime startsAt, LocalDateTime endsAt) {
        logger.debug("Finding events from {} to {}", startsAt, endsAt);

        try {
            // 1. Try cache first (fast path)
            Optional<List<Event>> cachedEvents = cacheStrategy.getEvents(startsAt, endsAt);

            if (cachedEvents.isPresent()) {
                logger.debug("Cache hit: {} events", cachedEvents.get().size());
                return cachedEvents.get();
            }

            // 2. Cache miss - fetch from database immediately (for UX)
            logger.debug("Cache miss - fetching from database");
            List<Event> events = databaseRepository.findByDateRange(startsAt, endsAt);

            // 3. Populate cache asynchronously (non-blocking)
            if (!events.isEmpty()) {
                populateCacheAsync(startsAt, endsAt, events);
            }

            return events;

        } catch (Exception e) {
            logger.error("Error in cached repository, falling back to database", e);
            // Graceful degradation - always try database as fallback
            return safeDatabaseFallback(startsAt, endsAt);
        }
    }

    @Override
    public void addNewEvents(List<Event> events) {
        try {
            // 1. Always write to database first (source of truth)
            databaseRepository.addNewEvents(events);

            // 2. Invalidate affected cache entries asynchronously
            if (!events.isEmpty()) {
                invalidateCacheAsync(events);
            }

        } catch (Exception e) {
            logger.error("Failed to add new events", e);
            throw e; // Propagate database errors
        }
    }

    /**
     * Async cache population - never blocks the user request
     */
    @Async("asyncExecutor")
    public void populateCacheAsync(LocalDateTime startsAt, LocalDateTime endsAt, List<Event> events) {
        try {
            cacheStrategy.putEvents(startsAt, endsAt, events);
            logger.debug("Async cache population completed for {} events", events.size());
        } catch (Exception e) {
            logger.warn("Async cache population failed: {}", e.getMessage());
        }
        CompletableFuture.completedFuture(null);
    }

    /**
     * Async cache invalidation - never blocks writes
     */
    @Async("asyncExecutor")
    public void invalidateCacheAsync(List<Event> events) {
        try {
            cacheStrategy.invalidateAffectedEntries(events);
            logger.debug("Async cache invalidation completed for {} events", events.size());
        } catch (Exception e) {
            logger.warn("Async cache invalidation failed: {}", e.getMessage());
        }
        CompletableFuture.completedFuture(null);
    }

    /**
     * Safe database fallback when cache fails
     */
    private List<Event> safeDatabaseFallback(LocalDateTime startsAt, LocalDateTime endsAt) {
        try {
            return databaseRepository.findByDateRange(startsAt, endsAt);
        } catch (Exception dbError) {
            logger.error("Database fallback also failed", dbError);
            // In a distributed system, this might trigger circuit breaker
            return List.of(); // Return empty list rather than throwing
        }
    }

    /**
     * Get cache performance statistics (for monitoring)
     */
    public String getCacheStats() {
        try {
            return cacheStrategy.getStats().summary();
        } catch (Exception e) {
            logger.warn("Failed to get cache stats", e);
            return "Cache stats unavailable";
        }
    }
}
