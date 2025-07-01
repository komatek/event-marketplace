package com.fever.marketplace.application;

/**
 * Interface representing the synchronization of events from an external provider.
 * This interface defines a contract for implementing event synchronization logic.
 */
public interface SyncEvents {

    /**
     * Synchronizes events from an external provider.
     * Implementations of this method should handle the logic for fetching and updating events
     * from the external provider into the application's system.
     */
    void syncEventsFromProvider();
}
