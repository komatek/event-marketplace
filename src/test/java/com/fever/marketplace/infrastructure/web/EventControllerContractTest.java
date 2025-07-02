package com.fever.marketplace.infrastructure.web;

import com.fever.marketplace.application.FindEvents;
import com.fever.marketplace.domain.model.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
class EventControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FindEvents findEvents;

    @Test
    void shouldReturnEventsWhenValidRequest() throws Exception {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        List<Event> mockEvents = List.of(
                createEvent("Concert in Madrid", LocalDate.of(2024, 12, 15)),
                createEvent("Theater Show", LocalDate.of(2024, 12, 20))
        );

        when(findEvents.execute(startsAt, endsAt)).thenReturn(mockEvents);

        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.events").isArray())
                .andExpect(jsonPath("$.data.events", hasSize(2)))
                .andExpect(jsonPath("$.data.events[0].title", is("Concert in Madrid")))
                .andExpect(jsonPath("$.data.events[0].id").exists())
                .andExpect(jsonPath("$.data.events[0].start_date", is("2024-12-15")))
                .andExpect(jsonPath("$.data.events[0].start_time", is("20:00:00")))
                .andExpect(jsonPath("$.data.events[0].end_date", is("2024-12-15")))
                .andExpect(jsonPath("$.data.events[0].end_time", is("23:00:00")))
                .andExpect(jsonPath("$.data.events[0].min_price", is(25.00)))
                .andExpect(jsonPath("$.data.events[0].max_price", is(100.00)))
                .andExpect(jsonPath("$.data.events[1].title", is("Theater Show")));
    }

    @Test
    void shouldReturnEmptyWhenNoEventsFound() throws Exception {
        // Given
        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        when(findEvents.execute(startsAt, endsAt)).thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.events").isArray())
                .andExpect(jsonPath("$.data.events", hasSize(0)));
    }

    @Test
    void shouldReturnBadRequestWhenStartsAtIsMissing() throws Exception {
        // When & Then
        mockMvc.perform(get("/search")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnBadRequestWhenEndsAtIsMissing() throws Exception {
        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnBadRequestWhenDateFormatIsInvalid() throws Exception {
        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "invalid-date")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnBadRequestWhenStartDateIsAfterEndDate() throws Exception {
        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-31T23:59:00")
                        .param("ends_at", "2024-12-01T10:00:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.events").isArray())
                .andExpect(jsonPath("$.data.events", hasSize(0)));
    }

    @Test
    void shouldReturnInternalServerErrorWhenServiceThrowsException() throws Exception {
        // Given
        when(findEvents.execute(any(), any())).thenThrow(new RuntimeException("Database connection failed"));

        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.events").isArray())
                .andExpect(jsonPath("$.data.events", hasSize(0)));
    }

    @Test
    void shouldHandleDifferentTimeZones() throws Exception {
        // Given
        List<Event> mockEvents = List.of(createEvent("Event", LocalDate.of(2024, 12, 15)));
        when(findEvents.execute(any(), any())).thenReturn(mockEvents);

        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:59")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(1)));
    }

    @Test
    void shouldHandleSpecialCharactersInEventTitles() throws Exception {
        // Given
        List<Event> mockEvents = List.of(
                createEventWithTitle("Café & Music", LocalDate.of(2024, 12, 15)),
                createEventWithTitle("Rock 'n' Roll Night", LocalDate.of(2024, 12, 16))
        );
        when(findEvents.execute(any(), any())).thenReturn(mockEvents);

        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[0].title", is("Café & Music")))
                .andExpect(jsonPath("$.data.events[1].title", is("Rock 'n' Roll Night")));
    }

    @Test
    void shouldHandleLargeNumberOfEvents() throws Exception {
        // Given
        List<Event> mockEvents = List.of();
        for (int i = 1; i <= 100; i++) {
            mockEvents = new java.util.ArrayList<>(mockEvents);
            mockEvents.add(
                    createEventWithTitle("Event " + i, LocalDate.of(2024, 12, i % 28 + 1))
            );
        }
        when(findEvents.execute(any(), any())).thenReturn(mockEvents);

        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(100)));
    }

    @Test
    void shouldValidateResponseStructure() throws Exception {
        // Given
        List<Event> mockEvents = List.of(createEvent("Test Event", LocalDate.of(2024, 12, 15)));
        when(findEvents.execute(any(), any())).thenReturn(mockEvents);

        // When & Then
        mockMvc.perform(get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.events").exists())
                .andExpect(jsonPath("$.data.events").isArray())
                .andExpect(jsonPath("$.data.events[0].id").exists())
                .andExpect(jsonPath("$.data.events[0].title").exists())
                .andExpect(jsonPath("$.data.events[0].start_date").exists())
                .andExpect(jsonPath("$.data.events[0].start_time").exists())
                .andExpect(jsonPath("$.data.events[0].end_date").exists())
                .andExpect(jsonPath("$.data.events[0].end_time").exists())
                .andExpect(jsonPath("$.data.events[0].min_price").exists())
                .andExpect(jsonPath("$.data.events[0].max_price").exists());
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

    private Event createEventWithTitle(String title, LocalDate date) {
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
