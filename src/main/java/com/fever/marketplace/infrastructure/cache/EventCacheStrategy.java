package com.fever.marketplace.infrastructure.cache;

import com.fever.marketplace.domain.model.Event;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Cache strategy abstraction
 * Allows different caching implementations without changing business logic
 */
public interface EventCacheStrategy {

    /**
     * Try to get events from cache for given date range
     * @return Optional.empty() if cache miss, otherwise cached events
     */
    Optional<List<Event>> getEvents(LocalDateTime startsAt, LocalDateTime endsAt);

    /**
     * Store events in cache
     */
    void putEvents(LocalDateTime startsAt, LocalDateTime endsAt, List<Event> events);

    /**
     * Invalidate cache entries that might be affected by new events
     */
    void invalidateAffectedEntries(List<Event> newEvents);

    /**
     * Get cache performance statistics
     */
    CacheStats getStats();
}
