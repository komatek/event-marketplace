package com.fever.marketplace.application;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import com.fever.marketplace.domain.port.out.ExternalEventProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncEventsUseCaseTest {

    @Mock
    private ExternalEventProvider externalProvider;

    @Mock
    private EventRepository eventRepository;

    private SyncEventsUseCase syncEventsUseCase;

    @BeforeEach
    void setUp() {
        syncEventsUseCase = new SyncEventsUseCase(externalProvider, eventRepository);
    }

    @Test
    void shouldSyncEventsSuccessfully() {
        // Given
        List<Event> events = List.of(createSampleEvent());
        when(externalProvider.fetchOnlineEvents())
                .thenReturn(CompletableFuture.completedFuture(events));

        // When
        syncEventsUseCase.syncEventsFromProvider();

        // Then
        verify(externalProvider).fetchOnlineEvents();
        verify(eventRepository).addNewEvents(events);
    }

    @Test
    void shouldHandleExternalProviderFailure() {
        // Given
        when(externalProvider.fetchOnlineEvents())
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Provider error")));

        // When
        syncEventsUseCase.syncEventsFromProvider();

        // Then
        verify(externalProvider).fetchOnlineEvents();
        verify(eventRepository, never()).addNewEvents(any());
    }

    @Test
    void shouldHandleEmptyEventList() {
        // Given
        when(externalProvider.fetchOnlineEvents())
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        // When
        syncEventsUseCase.syncEventsFromProvider();

        // Then
        verify(externalProvider).fetchOnlineEvents();
        verify(eventRepository).addNewEvents(List.of());
    }

    @Test
    void shouldHandleRepositoryFailure() {
        // Given
        List<Event> events = List.of(createSampleEvent());
        when(externalProvider.fetchOnlineEvents())
                .thenReturn(CompletableFuture.completedFuture(events));
        doThrow(new RuntimeException("Database error"))
                .when(eventRepository).addNewEvents(events);

        // When
        syncEventsUseCase.syncEventsFromProvider();

        // Then
        verify(externalProvider).fetchOnlineEvents();
        verify(eventRepository).addNewEvents(events);
        // Exception should be caught and logged, not propagated
    }

    @Test
    void shouldHandleInterruptedException() {
        // Given
        when(externalProvider.fetchOnlineEvents())
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    throw new RuntimeException("Interrupted");
                }));

        // When
        syncEventsUseCase.syncEventsFromProvider();

        // Then
        verify(externalProvider).fetchOnlineEvents();
        verify(eventRepository, never()).addNewEvents(any());
    }

    private Event createSampleEvent() {
        return new Event(
                UUID.randomUUID(), "Test Event",
                LocalDate.of(2024, 12, 15), LocalTime.of(20, 0),
                LocalDate.of(2024, 12, 15), LocalTime.of(23, 0),
                BigDecimal.valueOf(25.00), BigDecimal.valueOf(100.00)
        );
    }
}
