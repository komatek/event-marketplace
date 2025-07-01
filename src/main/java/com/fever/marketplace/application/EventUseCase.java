package com.fever.marketplace.application;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EventUseCase implements FindEvents {

    private static final Logger logger = LoggerFactory.getLogger(EventUseCase.class);

    private final EventRepository eventRepository;

    public EventUseCase(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<Event> execute(LocalDateTime startsAt, LocalDateTime endsAt) {
        logger.debug("Searching events from {} to {}", startsAt, endsAt);

        // Validate input parameters
        if (startsAt.isAfter(endsAt)) {
            logger.warn("Invalid date range: start {} is after end {}", startsAt, endsAt);
            return List.of();
        }

        try {
            // Repository handles caching logic transparently
            // This service remains stateless and focused on business logic
            List<Event> events = eventRepository.findByDateRange(startsAt, endsAt);

            logger.debug("Found {} events in date range", events.size());
            return events;

        } catch (Exception e) {
            logger.error("Error searching events", e);
            // Graceful degradation - return empty list rather than throwing
            return List.of();
        }
    }
}
