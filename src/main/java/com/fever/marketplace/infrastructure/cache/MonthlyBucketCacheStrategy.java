package com.fever.marketplace.infrastructure.cache;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import com.fever.marketplace.infrastructure.cache.bucket.BucketCacheConfig;
import com.fever.marketplace.infrastructure.cache.bucket.BucketOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
public class MonthlyBucketCacheStrategy implements EventCacheStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyBucketCacheStrategy.class);

    private final BucketOperations bucketOperations;
    private final BucketCacheConfig config;
    private final EventRepository databaseRepository;

    public MonthlyBucketCacheStrategy(
            BucketOperations bucketOperations,
            BucketCacheConfig config,
            @Qualifier("databaseEventRepository") EventRepository databaseRepository) {
        this.bucketOperations = bucketOperations;
        this.config = config;
        this.databaseRepository = databaseRepository;
    }

    @Override
    public Optional<List<Event>> getEvents(LocalDateTime startsAt, LocalDateTime endsAt) {
        try {
            List<YearMonth> requiredMonths = calculateRequiredMonths(startsAt, endsAt);

            // Check if query spans too many months
            if (requiredMonths.size() > config.getMaxBucketsPerQuery()) {
                logger.debug("Query spans {} months, exceeds max buckets ({}), skipping cache",
                        requiredMonths.size(), config.getMaxBucketsPerQuery());
                return Optional.empty();
            }

            // Analyze cache status for each month
            CacheAnalysis analysis = analyzeCacheStatus(requiredMonths);

            if (analysis.allMonthsCached()) {
                // Full cache hit - existing behavior
                return handleFullCacheHit(analysis, startsAt, endsAt);
            } else if (analysis.hasPartialCacheHit()) {
                // Partial cache hit - new enhanced behavior
                return handlePartialCacheHit(analysis, startsAt, endsAt);
            } else {
                // Complete cache miss
                return Optional.empty();
            }

        } catch (Exception e) {
            logger.error("Cache get error: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<List<Event>> handleFullCacheHit(CacheAnalysis analysis, LocalDateTime startsAt, LocalDateTime endsAt) {
        List<Event> allEvents = analysis.cachedEvents.values().stream()
                .flatMap(List::stream)
                .filter(event -> isEventInRange(event, startsAt, endsAt))
                .sorted(this::compareEvents)
                .distinct()
                .collect(Collectors.toList());

        logger.debug("Full cache hit: {} events from {} months", allEvents.size(), analysis.cachedMonths.size());
        return Optional.of(allEvents);
    }

    private Optional<List<Event>> handlePartialCacheHit(CacheAnalysis analysis, LocalDateTime startsAt, LocalDateTime endsAt) {
        try {
            // Step 1: Get events from cached months
            List<Event> cachedEvents = analysis.cachedEvents.values().stream()
                    .flatMap(List::stream)
                    .filter(event -> isEventInRange(event, startsAt, endsAt))
                    .collect(Collectors.toList());

            // Step 2: Query database for missing months only
            List<Event> databaseEvents = queryDatabaseForMissingMonths(analysis.missedMonths, startsAt, endsAt);

            // Step 3: Merge results
            Set<Event> mergedEvents = new HashSet<>(cachedEvents);
            mergedEvents.addAll(databaseEvents);

            List<Event> finalEvents = mergedEvents.stream()
                    .sorted(this::compareEvents)
                    .collect(Collectors.toList());

            // Step 4: Populate cache for missing months (async)
            populateMissingMonthsAsync(databaseEvents);

            logger.debug("Partial cache hit: {} cached + {} from DB = {} total events. Cached months: {}, DB months: {}",
                    cachedEvents.size(), databaseEvents.size(), finalEvents.size(),
                    analysis.cachedMonths, analysis.missedMonths);

            return Optional.of(finalEvents);

        } catch (Exception e) {
            logger.error("Error in partial cache hit handling: {}", e.getMessage(), e);
            // Fall back to complete database query
            return Optional.empty();
        }
    }

    private List<Event> queryDatabaseForMissingMonths(Set<YearMonth> missedMonths, LocalDateTime startsAt, LocalDateTime endsAt) {
        if (missedMonths.isEmpty()) {
            return Collections.emptyList();
        }

        // Calculate date range that covers only the missing months
        // This optimizes the database query to only fetch relevant data
        List<Event> allDbEvents = databaseRepository.findByDateRange(startsAt, endsAt);

        // Filter to only include events that belong to missing months
        return allDbEvents.stream()
                .filter(event -> {
                    YearMonth eventMonth = YearMonth.from(event.startDate());
                    return missedMonths.contains(eventMonth);
                })
                .collect(Collectors.toList());
    }

    @Async("asyncExecutor")
    public void populateMissingMonthsAsync(List<Event> databaseEvents) {
        try {
            if (!databaseEvents.isEmpty()) {
                // Group events by month and cache each month separately
                Map<YearMonth, Set<Event>> eventsByMonth = groupEventsByMonth(databaseEvents);

                for (Map.Entry<YearMonth, Set<Event>> entry : eventsByMonth.entrySet()) {
                    YearMonth month = entry.getKey();
                    LocalDate bucketKey = month.atDay(1);
                    List<Event> monthEvents = new ArrayList<>(entry.getValue());

                    bucketOperations.putBucketEvents(bucketKey, monthEvents);
                    logger.debug("Async cached missing month {} with {} events", month, monthEvents.size());
                }
            }
        } catch (Exception e) {
            logger.warn("Async population of missing months failed: {}", e.getMessage());
        }
        CompletableFuture.completedFuture(null);
    }

    private CacheAnalysis analyzeCacheStatus(List<YearMonth> requiredMonths) {
        Map<YearMonth, List<Event>> cachedEvents = new HashMap<>();
        Set<YearMonth> cachedMonths = new HashSet<>();
        Set<YearMonth> missedMonths = new HashSet<>();

        for (YearMonth month : requiredMonths) {
            LocalDate bucketKey = month.atDay(1);
            List<Event> monthEvents = bucketOperations.getBucketEvents(bucketKey);

            if (monthEvents != null) {
                cachedEvents.put(month, monthEvents);
                cachedMonths.add(month);
                logger.info("Cache hit for month: {} ({} events)", month, monthEvents.size());
            } else {
                missedMonths.add(month);
                logger.info("Cache miss for month: {}", month);
            }
        }

        return new CacheAnalysis(cachedEvents, cachedMonths, missedMonths, requiredMonths);
    }

    @Override
    public void putEvents(LocalDateTime startsAt, LocalDateTime endsAt, List<Event> events) {
        try {
            if (events.isEmpty()) {
                logger.debug("No events to cache");
                return;
            }

            Map<YearMonth, Set<Event>> eventsByMonth = groupEventsByMonth(events);

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
        }
    }

    @Override
    public void invalidateAffectedEntries(List<Event> newEvents) {
        // Existing implementation remains the same
        try {
            Set<YearMonth> affectedMonths = calculateAffectedMonths(newEvents);
            int invalidatedCount = 0;

            for (YearMonth month : affectedMonths) {
                LocalDate bucketKey = month.atDay(1);
                if (bucketOperations.invalidateBucket(bucketKey)) {
                    invalidatedCount++;
                }
            }

            logger.debug("Invalidated {} monthly buckets for {} new events", invalidatedCount, newEvents.size());

        } catch (Exception e) {
            logger.error("Cache invalidation error: {}", e.getMessage(), e);
        }
    }

    // Helper methods (same as before)
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

    // Inner class for cache analysis results
    private static class CacheAnalysis {
        final Map<YearMonth, List<Event>> cachedEvents;
        final Set<YearMonth> cachedMonths;
        final Set<YearMonth> missedMonths;
        final List<YearMonth> requiredMonths;

        CacheAnalysis(Map<YearMonth, List<Event>> cachedEvents, Set<YearMonth> cachedMonths,
                      Set<YearMonth> missedMonths, List<YearMonth> requiredMonths) {
            this.cachedEvents = cachedEvents;
            this.cachedMonths = cachedMonths;
            this.missedMonths = missedMonths;
            this.requiredMonths = requiredMonths;
        }

        boolean allMonthsCached() {
            return missedMonths.isEmpty();
        }

        boolean hasPartialCacheHit() {
            return !cachedMonths.isEmpty() && !missedMonths.isEmpty();
        }
    }
}
