package com.fever.marketplace.infrastructure.cache.bucket;

import com.fever.marketplace.domain.model.Event;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility for calculating date ranges and bucket operations
 */
@Component
public class BucketRangeCalculator {

    /**
     * Generate daily buckets for date range
     */
    public List<LocalDate> generateDailyBuckets(LocalDate start, LocalDate end) {
        List<LocalDate> buckets = new ArrayList<>();
        LocalDate current = start;

        while (!current.isAfter(end)) {
            buckets.add(current);
            current = current.plusDays(1);
        }

        return buckets;
    }

    /**
     * Calculate which buckets are affected by new events
     */
    public Set<LocalDate> calculateAffectedBuckets(List<Event> events) {
        Set<LocalDate> affectedDates = new HashSet<>();

        for (Event event : events) {
            LocalDate current = event.startDate();
            while (!current.isAfter(event.endDate())) {
                affectedDates.add(current);
                current = current.plusDays(1);
            }
        }

        return affectedDates;
    }
}
