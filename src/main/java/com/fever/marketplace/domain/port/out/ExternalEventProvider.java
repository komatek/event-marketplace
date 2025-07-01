package com.fever.marketplace.domain.port.out;

import com.fever.marketplace.domain.model.Event;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface representing an external event provider.
 * This interface defines a contract for fetching events from an external source asynchronously.
 */
public interface ExternalEventProvider {

    /**
     * Fetches a list of online events from the external provider.
     * The method returns a CompletableFuture to allow asynchronous processing.
     *
     * @return A CompletableFuture containing a list of events fetched from the external provider.
     */
    CompletableFuture<List<Event>> fetchOnlineEvents();

}
