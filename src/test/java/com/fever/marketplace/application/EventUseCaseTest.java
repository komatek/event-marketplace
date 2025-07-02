package com.fever.marketplace.application;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventUseCaseTest {

    @Mock
    private EventRepository eventRepository;

    private EventUseCase eventUseCase;

    @BeforeEach
    void setUp() {
        eventUseCase = new EventUseCase(eventRepository);
    }

    @Test
    void shouldReturnEventsFromRepository() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);
        List<Event> expectedEvents = List.of(createSampleEvent());

        when(eventRepository.findByDateRange(startsAt, endsAt)).thenReturn(expectedEvents);

        // When
        List<Event> result = eventUseCase.execute(startsAt, endsAt);

        // Then
        assertThat(result).isEqualTo(expectedEvents);
        verify(eventRepository).findByDateRange(startsAt, endsAt);
    }

    @Test
    void shouldReturnEmptyListWhenRepositoryThrowsException() {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        when(eventRepository.findByDateRange(startsAt, endsAt))
                .thenThrow(new RuntimeException("Database error"));

        // When
        List<Event> result = eventUseCase.execute(startsAt, endsAt);

        // Then
        assertThat(result).isEmpty();
        verify(eventRepository).findByDateRange(startsAt, endsAt);
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
