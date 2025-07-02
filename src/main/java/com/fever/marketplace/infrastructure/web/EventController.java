package com.fever.marketplace.infrastructure.web;

import com.fever.marketplace.application.FindEvents;
import com.fever.marketplace.infrastructure.web.dto.EventSearchResponse;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/search")
public class EventController {

    private static final Logger logger = LoggerFactory.getLogger(EventController.class);

    private final FindEvents findEvents;

    public EventController(FindEvents findEvents) {
        this.findEvents = findEvents;
    }

    @GetMapping
    public ResponseEntity<EventSearchResponse> searchEvents(
            @RequestParam("starts_at")
            @NotNull
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startsAt,

            @RequestParam("ends_at")
            @NotNull
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endsAt
    ) {
        logger.info("Searching events from {} to {}", startsAt, endsAt);

        if (startsAt.isAfter(endsAt)) {
            logger.warn("Invalid date range: start {} is after end {}", startsAt, endsAt);
            return ResponseEntity.badRequest()
                    .body(EventSearchResponse.empty());
        }

        try {
            var events = findEvents.execute(startsAt, endsAt);
            var response = EventSearchResponse.fromEvents(events);

            logger.info("Found {} events", events.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error searching events", e);
            return ResponseEntity.internalServerError()
                    .body(EventSearchResponse.empty());
        }
    }
}
