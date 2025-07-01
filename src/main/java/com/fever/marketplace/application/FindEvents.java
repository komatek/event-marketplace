package com.fever.marketplace.application;

import com.fever.marketplace.domain.model.Event;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Interface for finding events within a specified time range.
 * This interface defines a contract for querying events based on their start and end times.
 */
public interface FindEvents {

    /**
     * Executes a query to find events that occur within the specified time range.
     *
     * @param startsAt The start of the time range to filter events (inclusive).
     * @param endsAt The end of the time range to filter events (inclusive).
     * @return A list of events that fall within the specified time range.
     */
    List<Event> execute(LocalDateTime startsAt, LocalDateTime endsAt);

}
