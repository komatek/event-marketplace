package com.fever.marketplace.domain.port.out;

import com.fever.marketplace.domain.model.Event;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository port for Event aggregate
 * This is the contract that infrastructure must implement
 */
public interface EventRepository {
    /**
     * Find events within date range
     * Implementation details (caching, database, etc.) are hidden from domain
     */
    List<Event> findByDateRange(LocalDateTime startsAt, LocalDateTime endsAt);

    /**
     * Store new events (append-only)
     * Implementations should handle deduplication
     */
    void addNewEvents(List<Event> events);
}
