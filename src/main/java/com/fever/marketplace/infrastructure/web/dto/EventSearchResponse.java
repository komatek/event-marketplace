package com.fever.marketplace.infrastructure.web.dto;

import com.fever.marketplace.domain.model.Event;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record EventSearchResponse(
        EventData data
) {
    public static EventSearchResponse fromEvents(List<Event> events) {
        var eventDtos = events.stream()
                .map(EventDto::fromEvent)
                .toList();

        return new EventSearchResponse(new EventData(eventDtos));
    }

    public static EventSearchResponse empty() {
        return new EventSearchResponse(new EventData(List.of()));
    }

    public record EventData(
            List<EventDto> events
    ) {}

    public record EventDto(
            UUID id,
            String title,
            LocalDate start_date,
            LocalTime start_time,
            LocalDate end_date,
            LocalTime end_time,
            BigDecimal min_price,
            BigDecimal max_price
    ) {
        public static EventDto fromEvent(Event event) {
            return new EventDto(
                    event.id(),
                    event.title(),
                    event.startDate(),
                    event.startTime(),
                    event.endDate(),
                    event.endTime(),
                    event.minPrice(),
                    event.maxPrice()
            );
        }
    }
}
