// src/test/java/com/fever/marketplace/infrastructure/cache/MonthlyBucketCacheStrategyTest.java
package com.fever.marketplace.infrastructure.cache;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import com.fever.marketplace.infrastructure.cache.bucket.BucketCacheConfig;
import com.fever.marketplace.infrastructure.cache.bucket.BucketOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyBucketCacheStrategyTest {

    @Mock
    private BucketOperations bucketOperations;

    @Mock
    private BucketCacheConfig config;

    @Mock
    private EventRepository databaseRepository;

    private MonthlyBucketCacheStrategy cacheStrategy;

    @BeforeEach
    void setUp() {
        cacheStrategy = new MonthlyBucketCacheStrategy(bucketOperations, config, databaseRepository);
    }

    @Test
    void shouldReturnCachedEventsOnFullCacheHit() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> cachedEvents = List.of(
                createEvent("Event 1", LocalDate.of(2024, 12, 15)),
                createEvent("Event 2", LocalDate.of(2024, 12, 20))
        );

        // Mock config for max buckets check
        when(config.getMaxBucketsPerQuery()).thenReturn(24);
        when(bucketOperations.getBucketEvents(LocalDate.of(2024, 12, 1)))
                .thenReturn(cachedEvents);

        // When
        Optional<List<Event>> result = cacheStrategy.getEvents(startsAt, endsAt);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get()).extracting(Event::title)
                .containsExactly("Event 1", "Event 2");

        verify(bucketOperations).getBucketEvents(LocalDate.of(2024, 12, 1));
        verify(databaseRepository, never()).findByDateRange(any(), any());
    }

    @Test
    void shouldReturnEmptyOnCacheMiss() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        // Mock config for max buckets check
        when(config.getMaxBucketsPerQuery()).thenReturn(24);
        when(bucketOperations.getBucketEvents(LocalDate.of(2024, 12, 1))).thenReturn(null);

        // When
        Optional<List<Event>> result = cacheStrategy.getEvents(startsAt, endsAt);

        // Then
        assertThat(result).isEmpty();
        verify(bucketOperations).getBucketEvents(LocalDate.of(2024, 12, 1));
    }

    @Test
    void shouldHandlePartialCacheHit() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2025, 1, 31, 23, 59); // Spans 2 months

        List<Event> decemberEvents = List.of(createEvent("Dec Event", LocalDate.of(2024, 12, 15)));
        List<Event> dbEvents = List.of(
                createEvent("Dec Event", LocalDate.of(2024, 12, 15)), // Duplicate
                createEvent("Jan Event", LocalDate.of(2025, 1, 10))
        );

        // Mock config for max buckets check
        when(config.getMaxBucketsPerQuery()).thenReturn(24);

        // December cached, January not cached
        when(bucketOperations.getBucketEvents(LocalDate.of(2024, 12, 1))).thenReturn(decemberEvents);
        when(bucketOperations.getBucketEvents(LocalDate.of(2025, 1, 1))).thenReturn(null);
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(dbEvents);

        // When
        Optional<List<Event>> result = cacheStrategy.getEvents(startsAt, endsAt);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2); // Should merge and deduplicate
        assertThat(result.get()).extracting(Event::title)
                .containsExactlyInAnyOrder("Dec Event", "Jan Event");

        verify(bucketOperations).getBucketEvents(LocalDate.of(2024, 12, 1));
        verify(bucketOperations).getBucketEvents(LocalDate.of(2025, 1, 1));
        verify(databaseRepository).findByDateRange(startsAt, endsAt);
    }

    @Test
    void shouldSkipCacheForTooManyMonths() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2026, 12, 31, 23, 59); // 36 months

        when(config.getMaxBucketsPerQuery()).thenReturn(24); // Max 24 months

        // When
        Optional<List<Event>> result = cacheStrategy.getEvents(startsAt, endsAt);

        // Then
        assertThat(result).isEmpty();
        verify(bucketOperations, never()).getBucketEvents(any());
    }

    @Test
    void shouldFilterEventsByDateRange() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 10, 12, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 20, 18, 0);

        List<Event> cachedEvents = List.of(
                createEvent("Early Event", LocalDate.of(2024, 12, 5)), // Before range
                createEvent("In Range Event", LocalDate.of(2024, 12, 15)), // In range
                createEvent("Late Event", LocalDate.of(2024, 12, 25)) // After range
        );

        // Mock config for max buckets check
        when(config.getMaxBucketsPerQuery()).thenReturn(24);
        when(bucketOperations.getBucketEvents(LocalDate.of(2024, 12, 1)))
                .thenReturn(cachedEvents);

        // When
        Optional<List<Event>> result = cacheStrategy.getEvents(startsAt, endsAt);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).title()).isEqualTo("In Range Event");
    }

    @Test
    void shouldPutEventsIntoCorrectMonthlyBuckets() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2025, 1, 31, 23, 59);

        List<Event> events = List.of(
                createEvent("Dec Event", LocalDate.of(2024, 12, 15)),
                createEvent("Jan Event", LocalDate.of(2025, 1, 10)),
                createEvent("Multi-day Event", LocalDate.of(2024, 12, 30), LocalDate.of(2025, 1, 2))
        );

        when(bucketOperations.getBucketEvents(any())).thenReturn(null); // No existing events

        // When
        cacheStrategy.putEvents(startsAt, endsAt, events);

        // Then
        verify(bucketOperations).putBucketEvents(eq(LocalDate.of(2024, 12, 1)), argThat(list ->
                list.size() == 2 && // Dec Event + Multi-day Event
                        list.stream().anyMatch(e -> e.title().equals("Dec Event")) &&
                        list.stream().anyMatch(e -> e.title().equals("Multi-day Event"))
        ));

        verify(bucketOperations).putBucketEvents(eq(LocalDate.of(2025, 1, 1)), argThat(list ->
                list.size() == 2 && // Jan Event + Multi-day Event
                        list.stream().anyMatch(e -> e.title().equals("Jan Event")) &&
                        list.stream().anyMatch(e -> e.title().equals("Multi-day Event"))
        ));
    }

    @Test
    void shouldMergeWithExistingCachedEvents() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> newEvents = List.of(createEvent("New Event", LocalDate.of(2024, 12, 15)));
        List<Event> existingEvents = List.of(createEvent("Existing Event", LocalDate.of(2024, 12, 10)));

        when(bucketOperations.getBucketEvents(LocalDate.of(2024, 12, 1)))
                .thenReturn(existingEvents);

        // When
        cacheStrategy.putEvents(startsAt, endsAt, newEvents);

        // Then
        verify(bucketOperations).putBucketEvents(eq(LocalDate.of(2024, 12, 1)), argThat(list ->
                list.size() == 2 &&
                        list.stream().anyMatch(e -> e.title().equals("New Event")) &&
                        list.stream().anyMatch(e -> e.title().equals("Existing Event"))
        ));
    }

    @Test
    void shouldInvalidateAffectedMonthlyBuckets() {
        // Given
        List<Event> newEvents = List.of(
                createEvent("Dec Event", LocalDate.of(2024, 12, 15)),
                createEvent("Multi-month Event", LocalDate.of(2024, 12, 30), LocalDate.of(2025, 1, 5))
        );

        when(bucketOperations.invalidateBucket(any())).thenReturn(true);

        // When
        cacheStrategy.invalidateAffectedEntries(newEvents);

        // Then
        verify(bucketOperations).invalidateBucket(LocalDate.of(2024, 12, 1));
        verify(bucketOperations).invalidateBucket(LocalDate.of(2025, 1, 1));
    }

    @Test
    void shouldHandleEmptyEventsList() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        // When
        cacheStrategy.putEvents(startsAt, endsAt, Collections.emptyList());

        // Then
        verify(bucketOperations, never()).putBucketEvents(any(), any());
    }

    @Test
    void shouldHandleExceptionsGracefully() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        // Mock config first, then the exception
        when(config.getMaxBucketsPerQuery()).thenReturn(24);
        when(bucketOperations.getBucketEvents(any()))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // When
        Optional<List<Event>> result = cacheStrategy.getEvents(startsAt, endsAt);

        // Then
        assertThat(result).isEmpty(); // Should return empty on exception
    }

    @Test
    void shouldHandlePartialCacheHitWithMultipleMonths() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 11, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2025, 1, 31, 23, 59); // Spans 3 months

        List<Event> novemberEvents = List.of(createEvent("Nov Event", LocalDate.of(2024, 11, 15)));
        List<Event> dbEvents = List.of(
                createEvent("Nov Event", LocalDate.of(2024, 11, 15)), // Duplicate from cache
                createEvent("Dec Event", LocalDate.of(2024, 12, 10)),
                createEvent("Jan Event", LocalDate.of(2025, 1, 5))
        );

        // Mock config for max buckets check
        when(config.getMaxBucketsPerQuery()).thenReturn(24);

        // November cached, December and January not cached
        when(bucketOperations.getBucketEvents(LocalDate.of(2024, 11, 1))).thenReturn(novemberEvents);
        when(bucketOperations.getBucketEvents(LocalDate.of(2024, 12, 1))).thenReturn(null);
        when(bucketOperations.getBucketEvents(LocalDate.of(2025, 1, 1))).thenReturn(null);
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(dbEvents);

        // When
        Optional<List<Event>> result = cacheStrategy.getEvents(startsAt, endsAt);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(3); // Should merge and deduplicate
        assertThat(result.get()).extracting(Event::title)
                .containsExactlyInAnyOrder("Nov Event", "Dec Event", "Jan Event");
    }

    private Event createEvent(String title, LocalDate date) {
        return createEvent(title, date, date);
    }

    private Event createEvent(String title, LocalDate startDate, LocalDate endDate) {
        return new Event(
                UUID.randomUUID(), title,
                startDate, LocalTime.of(20, 0),
                endDate, LocalTime.of(23, 0),
                BigDecimal.valueOf(25.00), BigDecimal.valueOf(100.00)
        );
    }
}
