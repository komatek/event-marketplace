package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import com.fever.marketplace.infrastructure.cache.EventCacheStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached implementation of EventRepository using decorator pattern
 * Transparently adds caching to any EventRepository implementation
 */
@Repository
@Primary
public class CachedEventRepository implements EventRepository {

    private static final Logger logger = LoggerFactory.getLogger(CachedEventRepository.class);

    private final EventRepository databaseRepository;
    private final EventCacheStrategy cacheStrategy;

    public CachedEventRepository(
            @Qualifier("databaseEventRepository") EventRepository databaseRepository,
            EventCacheStrategy cacheStrategy) {
        this.databaseRepository = databaseRepository;
        this.cacheStrategy = cacheStrategy;
    }

    @Override
    public List<Event> findByDateRange(LocalDateTime startsAt, LocalDateTime endsAt) {
        logger.debug("Finding events from {} to {}", startsAt, endsAt);

        try {
            Optional<List<Event>> cachedEvents = cacheStrategy.getEvents(startsAt, endsAt);

            if (cachedEvents.isPresent()) {
                logger.debug("Cache hit: {} events", cachedEvents.get().size());
                return cachedEvents.get();
            }

            logger.debug("Cache miss - fetching from database");
            List<Event> events = databaseRepository.findByDateRange(startsAt, endsAt);

            if (!events.isEmpty()) {
                populateCacheAsync(startsAt, endsAt, events);
            }

            return events;

        } catch (Exception e) {
            logger.error("Error in cached repository, falling back to database", e);
            return safeDatabaseFallback(startsAt, endsAt);
        }
    }

    @Override
    @Transactional
    public void addNewEvents(List<Event> events) {
        if (events.isEmpty()) {
            logger.debug("No events to add");
            return;
        }

        try {
            invalidateCacheSync(events);

            databaseRepository.addNewEvents(events);

            logger.info("Successfully added {} new events", events.size());
        } catch (Exception e) {
            logger.error("Failed to add new events", e);
            throw e;
        }
    }

    /**
     * Synchronous cache invalidation before database writes
     * Simple and ensures cache consistency
     */
    private void invalidateCacheSync(List<Event> events) {
        try {
            cacheStrategy.invalidateAffectedEntries(events);
            logger.debug("Cache invalidated before database write for {} events", events.size());
        } catch (Exception e) {
            logger.warn("Cache invalidation failed, proceeding with database write: {}", e.getMessage());
        }
    }

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

    private List<Event> safeDatabaseFallback(LocalDateTime startsAt, LocalDateTime endsAt) {
        try {
            return databaseRepository.findByDateRange(startsAt, endsAt);
        } catch (Exception dbError) {
            logger.error("Database fallback also failed", dbError);
            return List.of();
        }
    }

}
