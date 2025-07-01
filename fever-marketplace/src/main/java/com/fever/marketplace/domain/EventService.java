package com.fever.marketplace.domain;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.EventRepository;
import com.fever.marketplace.domain.port.in.EventSearchService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EventService implements EventSearchService {

    private static final Logger logger = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public List<Event> searchEvents(LocalDateTime startsAt, LocalDateTime endsAt) {
        logger.info("Searching events from {} to {}", startsAt, endsAt);

        List<Event> events = eventRepository.findByDateRange(startsAt, endsAt)
                .stream()
                .filter(event -> "online".equals(event.sellMode()))
                .toList();

        logger.info("Found {} events", events.size());
        return events;
    }
}
