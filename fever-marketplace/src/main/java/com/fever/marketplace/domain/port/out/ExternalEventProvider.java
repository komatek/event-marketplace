package com.fever.marketplace.domain.port;

import com.fever.marketplace.domain.model.Event;
import java.util.List;

/**
 * Port for fetching events from external providers
 * Domain doesn't care about HTTP, XML, REST, etc.
 */
public interface ExternalEventProvider {
    /**
     * Fetch online events from external source
     * @return List of events that are available for online purchase
     */
    List<Event> fetchOnlineEvents();

    /**
     * Check if provider is available
     * @return true if provider is responding
     */
    boolean isHealthy();
}
