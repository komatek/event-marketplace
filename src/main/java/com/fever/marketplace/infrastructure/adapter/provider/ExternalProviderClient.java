package com.fever.marketplace.infrastructure.adapter.provider;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.ExternalEventProvider;
import com.fever.marketplace.infrastructure.adapter.mapper.EventMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class ExternalProviderClient implements ExternalEventProvider {

    private static final Logger logger = LoggerFactory.getLogger(ExternalProviderClient.class);
    private final ExternalEventApi eventApi;
    private final EventMapper eventMapper;

    public ExternalProviderClient(ExternalEventApi eventApi, EventMapper eventMapper) {
        this.eventApi = eventApi;
        this.eventMapper = eventMapper;
    }

    @Override
    @CircuitBreaker(name = "external-provider", fallbackMethod = "fallbackFetchEvents")
    @Retry(name = "external-provider")
    @TimeLimiter(name = "external-provider")
    public CompletableFuture<List<Event>> fetchOnlineEvents() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Fetching events from external provider via Retrofit");
                var response = eventApi.fetchEvents().execute();
                if (response.isSuccessful() && response.body() != null) {
                    var output = response.body().output();
                    if (output != null && output.basePlans() != null) {
                        var basePlans = output.basePlans();
                        return eventMapper.mapToOnlineEvents(basePlans);
                    }
                }
                logger.warn("Empty or failed response from external provider");
                return Collections.emptyList();
            } catch (Exception e) {
                logger.error("Exception fetching events from provider: {}", e.getMessage());
                throw new RuntimeException("Failed to fetch external events", e);
            }
        });
    }

    public CompletableFuture<List<Event>> fallbackFetchEvents(Exception ex) {
        logger.warn("Fallback triggered for external provider: {}", ex.getMessage());
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
