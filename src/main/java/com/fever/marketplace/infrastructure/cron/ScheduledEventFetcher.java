package com.fever.marketplace.infrastructure.cron;

import com.fever.marketplace.application.SyncEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledEventFetcher {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledEventFetcher.class);
    private final SyncEvents syncService;

    public ScheduledEventFetcher(SyncEvents syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedRate = 60000) // every 60 seconds
    public void fetch() {
        logger.info("Running scheduled fetch for external events");
        syncService.syncEventsFromProvider();
    }
}
