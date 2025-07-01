package com.fever.marketplace.infrastructure.cache;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.cache.bucket.BucketCacheConfig;
import com.fever.marketplace.infrastructure.cache.bucket.BucketOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class MonthlyBucketCacheStrategy implements EventCacheStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyBucketCacheStrategy.class);

    private final BucketOperations bucketOperations;
    private final BucketCacheConfig config;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    public MonthlyBucketCacheStrategy(BucketOperations bucketOperations, BucketCacheConfig config) {
        this.bucketOperations = bucketOperations;
        this.config = config;
    }

    @Override
    public Optional<List<Event>> getEvents(LocalDateTime startsAt, LocalDateTime endsAt) {
        try {
            // Calculate required monthly buckets
            List<YearMonth> requiredMonths = calculateRequiredMonths(startsAt, endsAt);

            // Check if query spans too many months (configurable limit)
            if (requiredMonths.size() > config.getMaxBucketsPerQuery()) {
                logger.debug("Query spans {} months, exceeds max buckets ({}), skipping cache",
                        requiredMonths.size(), config.getMaxBucketsPerQuery());
                misses.incrementAndGet();
                return Optional.empty();
            }

            // Try to get all required months from cache
            Map<YearMonth, List<Event>> monthlyEvents = new HashMap<>();
            boolean hasAllMonths = true;

            for (YearMonth month : requiredMonths) {
                LocalDate bucketKey = month.atDay(1); // Use first day of month as bucket key
                List<Event> monthEvents = bucketOperations.getBucketEvents(bucketKey);

                if (monthEvents != null) {
                    monthlyEvents.put(month, monthEvents);
                    logger.debug("Cache hit for month: {} ({} events)", month, monthEvents.size());
                } else {
                    hasAllMonths = false;
                    logger.debug("Cache miss for month: {}", month);
                    break;
                }
            }

            if (!hasAllMonths) {
                misses.incrementAndGet();
                return Optional.empty();
            }

            // Aggregate and filter events from all months
            List<Event> filteredEvents = monthlyEvents.values().stream()
                    .flatMap(List::stream)
                    .filter(event -> isEventInRange(event, startsAt, endsAt))
                    .sorted(this::compareEvents)
                    .distinct() // Remove duplicates for events spanning multiple months
                    .collect(Collectors.toList());

            hits.incrementAndGet();
            logger.debug("Cache hit: {} events from {} months", filteredEvents.size(), requiredMonths.size());
            return Optional.of(filteredEvents);

        } catch (Exception e) {
            logger.error("Cache get error: {}", e.getMessage(), e);
            errors.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void putEvents(LocalDateTime startsAt, LocalDateTime endsAt, List<Event> events) {
        try {
            if (events.isEmpty()) {
                logger.debug("No events to cache");
                return;
            }

            // Group events by month they belong to
            Map<YearMonth, Set<Event>> eventsByMonth = groupEventsByMonth(events);

            // Cache each month's events
            for (Map.Entry<YearMonth, Set<Event>> entry : eventsByMonth.entrySet()) {
                YearMonth month = entry.getKey();
                LocalDate bucketKey = month.atDay(1);
                List<Event> monthEvents = new ArrayList<>(entry.getValue());

                // Merge with existing events for this month (if any)
                List<Event> existingEvents = bucketOperations.getBucketEvents(bucketKey);
                if (existingEvents != null) {
                    Set<Event> mergedEvents = new HashSet<>(existingEvents);
                    mergedEvents.addAll(monthEvents);
                    monthEvents = new ArrayList<>(mergedEvents);
                }

                bucketOperations.putBucketEvents(bucketKey, monthEvents);
                logger.debug("Cached month {} with {} events", month, monthEvents.size());
            }

            logger.debug("Cached events across {} monthly buckets", eventsByMonth.size());

        } catch (Exception e) {
            logger.error("Cache put error: {}", e.getMessage(), e);
            errors.incrementAndGet();
        }
    }

    @Override
    public void invalidateAffectedEntries(List<Event> newEvents) {
        if (config.isAsyncInvalidation()) {
            invalidateAsync(newEvents);
        } else {
            performInvalidation(newEvents);
        }
    }

    @Async("asyncExecutor")
    public CompletableFuture<Void> invalidateAsync(List<Event> newEvents) {
        performInvalidation(newEvents);
        return CompletableFuture.completedFuture(null);
    }

    private void performInvalidation(List<Event> newEvents) {
        try {
            // Calculate all affected monthly buckets
            Set<YearMonth> affectedMonths = calculateAffectedMonths(newEvents);

            int invalidatedCount = 0;
            for (YearMonth month : affectedMonths) {
                LocalDate bucketKey = month.atDay(1);
                if (bucketOperations.invalidateBucket(bucketKey)) {
                    invalidatedCount++;
                }
            }

            invalidations.addAndGet(invalidatedCount);
            logger.debug("Invalidated {} monthly buckets for {} new events", invalidatedCount, newEvents.size());

        } catch (Exception e) {
            logger.error("Cache invalidation error: {}", e.getMessage(), e);
            errors.incrementAndGet();
        }
    }

    private List<YearMonth> calculateRequiredMonths(LocalDateTime startsAt, LocalDateTime endsAt) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth startMonth = YearMonth.from(startsAt);
        YearMonth endMonth = YearMonth.from(endsAt);

        YearMonth current = startMonth;
        while (!current.isAfter(endMonth)) {
            months.add(current);
            current = current.plusMonths(1);
        }

        return months;
    }

    private Map<YearMonth, Set<Event>> groupEventsByMonth(List<Event> events) {
        Map<YearMonth, Set<Event>> eventsByMonth = new HashMap<>();

        for (Event event : events) {
            YearMonth startMonth = YearMonth.from(event.startDate());
            YearMonth endMonth = YearMonth.from(event.endDate());

            YearMonth current = startMonth;
            while (!current.isAfter(endMonth)) {
                eventsByMonth.computeIfAbsent(current, k -> new HashSet<>()).add(event);
                current = current.plusMonths(1);
            }
        }

        return eventsByMonth;
    }

    private Set<YearMonth> calculateAffectedMonths(List<Event> events) {
        Set<YearMonth> affectedMonths = new HashSet<>();

        for (Event event : events) {
            YearMonth startMonth = YearMonth.from(event.startDate());
            YearMonth endMonth = YearMonth.from(event.endDate());

            YearMonth current = startMonth;
            while (!current.isAfter(endMonth)) {
                affectedMonths.add(current);
                current = current.plusMonths(1);
            }
        }

        return affectedMonths;
    }

    private boolean isEventInRange(Event event, LocalDateTime startsAt, LocalDateTime endsAt) {
        LocalDateTime eventStart = LocalDateTime.of(event.startDate(), event.startTime());
        LocalDateTime eventEnd = LocalDateTime.of(event.endDate(), event.endTime());

        return !eventStart.isAfter(endsAt) && !eventEnd.isBefore(startsAt);
    }

    private int compareEvents(Event e1, Event e2) {
        int dateCompare = e1.startDate().compareTo(e2.startDate());
        if (dateCompare != 0) {
            return dateCompare;
        }
        return e1.startTime().compareTo(e2.startTime());
    }
}
