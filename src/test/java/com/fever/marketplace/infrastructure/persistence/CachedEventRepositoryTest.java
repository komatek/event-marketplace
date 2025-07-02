package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import com.fever.marketplace.infrastructure.cache.EventCacheStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachedEventRepositoryTest {

    @Mock
    private EventRepository databaseRepository;

    @Mock
    private EventCacheStrategy cacheStrategy;

    private CachedEventRepository cachedRepository;

    @BeforeEach
    void setUp() {
        cachedRepository = new CachedEventRepository(databaseRepository, cacheStrategy);
    }

    @Test
    void shouldReturnCachedEventsOnCacheHit() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> cachedEvents = List.of(
                createEvent("Cached Event 1", LocalDate.of(2024, 12, 15)),
                createEvent("Cached Event 2", LocalDate.of(2024, 12, 20))
        );

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.of(cachedEvents));

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).isEqualTo(cachedEvents);

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository, never()).findByDateRange(any(), any());
        verify(cacheStrategy, never()).putEvents(any(), any(), any());
    }

    @Test
    void shouldFetchFromDatabaseOnCacheMiss() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> databaseEvents = List.of(
                createEvent("DB Event 1", LocalDate.of(2024, 12, 15)),
                createEvent("DB Event 2", LocalDate.of(2024, 12, 20))
        );

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.empty());
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(databaseEvents);

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).isEqualTo(databaseEvents);

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository).findByDateRange(startsAt, endsAt);

        // Verify async cache population is triggered
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                verify(cacheStrategy).putEvents(eq(startsAt), eq(endsAt), eq(databaseEvents))
        );
    }

    @Test
    void shouldNotPopulateCacheWhenDatabaseReturnsEmptyResults() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.empty());
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(Collections.emptyList());

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result).isEmpty();

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository).findByDateRange(startsAt, endsAt);

        // Verify cache is NOT populated for empty results
        verify(cacheStrategy, never()).putEvents(any(), any(), any());
    }

    @Test
    void shouldFallbackToDatabaseWhenCacheThrowsException() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> databaseEvents = List.of(createEvent("Fallback Event", LocalDate.of(2024, 12, 15)));

        when(cacheStrategy.getEvents(startsAt, endsAt))
                .thenThrow(new RuntimeException("Cache service unavailable"));
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(databaseEvents);

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result).isEqualTo(databaseEvents);

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository).findByDateRange(startsAt, endsAt);
    }

    @Test
    void shouldReturnEmptyListWhenBothCacheAndDatabaseFail() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        when(cacheStrategy.getEvents(startsAt, endsAt))
                .thenThrow(new RuntimeException("Cache service unavailable"));
        when(databaseRepository.findByDateRange(startsAt, endsAt))
                .thenThrow(new RuntimeException("Database connection failed"));

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result).isEmpty();

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository).findByDateRange(startsAt, endsAt);
    }

    @Test
    void shouldInvalidateCacheAndAddEventsSuccessfully() {
        // Given
        List<Event> newEvents = List.of(
                createEvent("New Event 1", LocalDate.of(2024, 12, 25)),
                createEvent("New Event 2", LocalDate.of(2024, 12, 26))
        );

        // When
        cachedRepository.addNewEvents(newEvents);

        // Then
        verify(cacheStrategy).invalidateAffectedEntries(newEvents);
        verify(databaseRepository).addNewEvents(newEvents);

        // Verify order: invalidation happens before database write
        var inOrder = inOrder(cacheStrategy, databaseRepository);
        inOrder.verify(cacheStrategy).invalidateAffectedEntries(newEvents);
        inOrder.verify(databaseRepository).addNewEvents(newEvents);
    }

    @Test
    void shouldAddEventsEvenWhenCacheInvalidationFails() {
        // Given
        List<Event> newEvents = List.of(createEvent("New Event", LocalDate.of(2024, 12, 25)));

        doThrow(new RuntimeException("Cache invalidation failed"))
                .when(cacheStrategy).invalidateAffectedEntries(newEvents);

        // When
        cachedRepository.addNewEvents(newEvents);

        // Then - Database operation should still proceed
        verify(cacheStrategy).invalidateAffectedEntries(newEvents);
        verify(databaseRepository).addNewEvents(newEvents);
    }

    @Test
    void shouldPropagateExceptionWhenDatabaseAddFails() {
        // Given
        List<Event> newEvents = List.of(createEvent("New Event", LocalDate.of(2024, 12, 25)));

        doThrow(new RuntimeException("Database write failed"))
                .when(databaseRepository).addNewEvents(newEvents);

        // When & Then
        assertThatThrownBy(() -> cachedRepository.addNewEvents(newEvents))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database write failed");

        verify(cacheStrategy).invalidateAffectedEntries(newEvents);
        verify(databaseRepository).addNewEvents(newEvents);
    }

    @Test
    void shouldSkipProcessingWhenAddingEmptyEventsList() {
        // Given
        List<Event> emptyEvents = Collections.emptyList();

        // When
        cachedRepository.addNewEvents(emptyEvents);

        // Then
        verifyNoInteractions(cacheStrategy);
        verifyNoInteractions(databaseRepository);
    }

    @Test
    void shouldHandleAsyncCachePopulationFailure() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> databaseEvents = List.of(createEvent("Event", LocalDate.of(2024, 12, 15)));

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.empty());
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(databaseEvents);

        // Cache population fails
        doThrow(new RuntimeException("Async cache population failed"))
                .when(cacheStrategy).putEvents(any(), any(), any());

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then - Should still return database results despite cache population failure
        assertThat(result).hasSize(1);
        assertThat(result).isEqualTo(databaseEvents);

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository).findByDateRange(startsAt, endsAt);

        // Verify async cache population was attempted
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                verify(cacheStrategy).putEvents(eq(startsAt), eq(endsAt), eq(databaseEvents))
        );
    }

    @Test
    void shouldPassCorrectParametersToCache() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 30);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 45);

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.empty());
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(Collections.emptyList());

        // When
        cachedRepository.findByDateRange(startsAt, endsAt);

        // Then - Verify exact parameters are passed
        ArgumentCaptor<LocalDateTime> startsAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endsAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(cacheStrategy).getEvents(startsAtCaptor.capture(), endsAtCaptor.capture());
        verify(databaseRepository).findByDateRange(startsAtCaptor.capture(), endsAtCaptor.capture());

        // Verify all captured values are correct
        assertThat(startsAtCaptor.getAllValues()).containsOnly(startsAt);
        assertThat(endsAtCaptor.getAllValues()).containsOnly(endsAt);
    }

    @Test
    void shouldHandleCacheReturningEmptyOptional() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> databaseEvents = List.of(createEvent("Event", LocalDate.of(2024, 12, 15)));

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.empty());
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(databaseEvents);

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result).isEqualTo(databaseEvents);

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository).findByDateRange(startsAt, endsAt);
    }

    @Test
    void shouldHandleCacheReturningEmptyList() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.of(Collections.emptyList()));

        // When
        List<Event> result = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result).isEmpty();

        verify(cacheStrategy).getEvents(startsAt, endsAt);
        verify(databaseRepository, never()).findByDateRange(any(), any());
    }

    @Test
    void shouldHandleNullEventsList() {
        // Given
        List<Event> nullEvents = null;

        // When & Then
        assertThatThrownBy(() -> cachedRepository.addNewEvents(nullEvents))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldVerifyAsyncCachePopulationParameters() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> databaseEvents = List.of(
                createEvent("Event 1", LocalDate.of(2024, 12, 15)),
                createEvent("Event 2", LocalDate.of(2024, 12, 20))
        );

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.empty());
        when(databaseRepository.findByDateRange(startsAt, endsAt)).thenReturn(databaseEvents);

        // When
        cachedRepository.findByDateRange(startsAt, endsAt);

        // Then - Verify async cache population gets correct parameters
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            ArgumentCaptor<LocalDateTime> startsAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> endsAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<List<Event>> eventsCaptor = ArgumentCaptor.forClass(List.class);

            verify(cacheStrategy).putEvents(
                    startsAtCaptor.capture(),
                    endsAtCaptor.capture(),
                    eventsCaptor.capture()
            );

            assertThat(startsAtCaptor.getValue()).isEqualTo(startsAt);
            assertThat(endsAtCaptor.getValue()).isEqualTo(endsAt);
            assertThat(eventsCaptor.getValue()).isEqualTo(databaseEvents);
        });
    }

    @Test
    void shouldHandleMultipleConcurrentReads() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> cachedEvents = List.of(createEvent("Cached Event", LocalDate.of(2024, 12, 15)));

        when(cacheStrategy.getEvents(startsAt, endsAt)).thenReturn(Optional.of(cachedEvents));

        // When - Multiple calls
        List<Event> result1 = cachedRepository.findByDateRange(startsAt, endsAt);
        List<Event> result2 = cachedRepository.findByDateRange(startsAt, endsAt);
        List<Event> result3 = cachedRepository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(result1).isEqualTo(cachedEvents);
        assertThat(result2).isEqualTo(cachedEvents);
        assertThat(result3).isEqualTo(cachedEvents);

        verify(cacheStrategy, times(3)).getEvents(startsAt, endsAt);
        verify(databaseRepository, never()).findByDateRange(any(), any());
    }

    private Event createEvent(String title, LocalDate date) {
        return new Event(
                UUID.randomUUID(),
                title,
                date,
                LocalTime.of(20, 0),
                date,
                LocalTime.of(23, 0),
                BigDecimal.valueOf(25.00),
                BigDecimal.valueOf(100.00)
        );
    }
}
