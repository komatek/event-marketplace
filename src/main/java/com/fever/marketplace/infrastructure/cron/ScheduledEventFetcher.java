package com.fever.marketplace.infrastructure.cron;

import com.fever.marketplace.application.SyncEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fever.sync.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledEventFetcher {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledEventFetcher.class);
    private final SyncEvents syncEvents;

    public ScheduledEventFetcher(SyncEvents syncEvents) {
        this.syncEvents = syncEvents;
    }

    @Scheduled(fixedRateString = "${fever.sync.interval}")
    public void fetch() {
        logger.info("Running scheduled fetch for external events");
        try {
            syncEvents.syncEventsFromProvider();
        } catch (Exception e) {
            logger.error("Scheduled sync failed", e);
            // Don't propagate exception to avoid stopping the scheduler
        }
    }
}
