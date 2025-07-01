package com.fever.marketplace.infrastructure.adapter.mapper;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.adapter.provider.xml.BasePlanXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.PlanXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.ZoneXml;
import java.math.BigDecimal;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Component
public class EventMapper {

    private static final Logger logger = LoggerFactory.getLogger(EventMapper.class);

    /**
     * Maps provider XML data to domain Events
     * ONLY processes events with sell_mode = "online"
     */
    public List<Event> mapToOnlineEvents(List<BasePlanXml> basePlans) {
        return basePlans.stream()
                .filter(basePlan -> "online".equals(basePlan.sellMode())) // Only online events
                .flatMap(basePlan -> basePlan.plans().stream()
                        .map(plan -> mapToEvent(basePlan, plan)))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Maps a single BasePlan + Plan to Event
     */
    private Event mapToEvent(BasePlanXml basePlan, PlanXml plan) {
        try {
            // Parse dates
            LocalDateTime startDateTime = parseDateTime(plan.startDate());
            LocalDateTime endDateTime = parseDateTime(plan.endDate());

            // Calculate price range from zones
            PriceRange priceRange = calculatePriceRange(plan.zones());

            return new Event(
                    UUID.randomUUID(),
                    basePlan.title(),
                    startDateTime.toLocalDate(),
                    startDateTime.toLocalTime(),
                    endDateTime.toLocalDate(),
                    endDateTime.toLocalTime(),
                    priceRange.minPrice(),
                    priceRange.maxPrice()
            );

        } catch (Exception e) {
            logger.warn("Failed to map event for plan: {} - {}", basePlan.title(), e.getMessage());
            return null;
        }
    }

    /**
     * Calculate min/max prices from zones
     * Only considers zones with capacity > 0
     */
    private PriceRange calculatePriceRange(List<ZoneXml> zones) {
        BigDecimal minPrice = zones.stream()
                .filter(zone -> zone.capacity() > 0) // Only available zones
                .map(zone -> BigDecimal.valueOf(zone.price()))
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal maxPrice = zones.stream()
                .filter(zone -> zone.capacity() > 0) // Only available zones
                .map(zone -> BigDecimal.valueOf(zone.price()))
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        return new PriceRange(minPrice, maxPrice);
    }

    /**
     * Parse provider date format to LocalDateTime
     */
    private LocalDateTime parseDateTime(String dateTime) {
        return LocalDateTime.parse(dateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * Helper record for price calculations
     */
    private record PriceRange(BigDecimal minPrice, BigDecimal maxPrice) {}
}
