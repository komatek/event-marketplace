package com.fever.marketplace.application;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import com.fever.marketplace.domain.port.out.ExternalEventProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SyncEvents {

    private static final Logger logger = LoggerFactory.getLogger(SyncEvents.class);
    private final ExternalEventProvider externalProvider;
    private final EventRepository eventRepository;

    public SyncEvents(ExternalEventProvider externalProvider, EventRepository eventRepository) {
        this.externalProvider = externalProvider;
        this.eventRepository = eventRepository;
    }

    public void syncEventsFromProvider() {
        logger.info("Starting sync of external events");
        try {
            List<Event> newEvents = externalProvider.fetchOnlineEvents().get();
            logger.info("Fetched {} events, storing to DB...", newEvents.size());
            eventRepository.addNewEvents(newEvents);
        } catch (Exception e) {
            logger.error("Failed to sync events from provider", e);
        }
    }
}
