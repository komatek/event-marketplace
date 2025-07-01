package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.EventRepository;
import com.fever.marketplace.infrastructure.cache.EventCacheStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Cached implementation of EventRepository
 * Uses composition to combine caching with database operations
 * This is the primary repository that the application layer uses
 */
@Repository
@Primary
public class CachedEventRepository implements EventRepository {

    private static final Logger logger = LoggerFactory.getLogger(CachedEventRepository.class);

    private final EventRepository databaseRepository;
    private final EventCacheStrategy cacheStrategy;

    public CachedEventRepository(DatabaseEventRepository databaseRepository,
                                 EventCacheStrategy cacheStrategy) {
        this.databaseRepository = databaseRepository;
        this.cacheStrategy = cacheStrategy;
    }

    @Override
    public List<Event> findByDateRange(LocalDateTime startsAt, LocalDateTime endsAt) {
        logger.debug("Finding events from {} to {}", startsAt, endsAt);

        try {
            // Try cache first
            Optional<List<Event>> cachedEvents = cacheStrategy.getEvents(startsAt, endsAt);

            if (cachedEvents.isPresent()) {
                logger.debug("Cache hit: {} events", cachedEvents.get().size());
                return cachedEvents.get();
            }

            // Cache miss - fetch from database
            logger.debug("Cache miss - fetching from database");
            List<Event> events = databaseRepository.findByDateRange(startsAt, endsAt);

            // Store in cache for future requests
            if (!events.isEmpty()) {
                cacheStrategy.putEvents(startsAt, endsAt, events);
            }

            return events;

        } catch (Exception e) {
            logger.error("Error in cached repository, falling back to database", e);
            // Graceful degradation - always try database as fallback
            try {
                return databaseRepository.findByDateRange(startsAt, endsAt);
            } catch (Exception dbError) {
                logger.error("Database fallback also failed", dbError);
                throw new RuntimeException("Repository completely unavailable", dbError);
            }
        }
    }

    @Override
    public void addNewEvents(List<Event> events) {
        try {
            // Always write to database first (source of truth)
            databaseRepository.addNewEvents(events);

            // Invalidate affected cache entries
            if (!events.isEmpty()) {
                cacheStrategy.invalidateAffectedEntries(events);
            }

        } catch (Exception e) {
            logger.error("Failed to add new events", e);
            throw e; // Propagate database errors
        }
    }

    /**
     * Get cache performance statistics
     * Useful for monitoring and debugging
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
