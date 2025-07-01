package com.fever.marketplace.infrastructure.adapter.mapper;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.adapter.provider.xml.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EventMapperTest {

    private EventMapper eventMapper;

    @BeforeEach
    void setUp() {
        eventMapper = new EventMapper();
    }

    @Test
    void shouldMapOnlineEventsOnly() {
        // Given
        List<BasePlanXml> basePlans = List.of(
                createBasePlan("online", "Online Concert"),
                createBasePlan("offline", "Offline Event")
        );

        // When
        List<Event> events = eventMapper.mapToOnlineEvents(basePlans);

        // Then
        assertThat(events).hasSize(1);
        assertThat(events.get(0).title()).isEqualTo("Online Concert");
    }

    @Test
    void shouldCalculatePriceRangeCorrectly() {
        // Given
        List<ZoneXml> zones = List.of(
                new ZoneXml("1", 100, 50.0, "VIP", false),
                new ZoneXml("2", 200, 25.0, "General", false),
                new ZoneXml("3", 0, 75.0, "Sold Out", false) // Should be ignored (capacity = 0)
        );

        PlanXml plan = new PlanXml(
                "plan1", "2024-12-15T20:00:00", "2024-12-15T23:00:00",
                "2024-12-01T00:00:00", "2024-12-14T23:59:59", false, zones
        );

        BasePlanXml basePlan = new BasePlanXml("base1", "online", "Test Event", "org1", List.of(plan));

        // When
        List<Event> events = eventMapper.mapToOnlineEvents(List.of(basePlan));

        // Then
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.minPrice()).isEqualTo(BigDecimal.valueOf(25.0));
        assertThat(event.maxPrice()).isEqualTo(BigDecimal.valueOf(50.0));
    }

    @Test
    void shouldHandleInvalidDateFormat() {
        // Given
        PlanXml invalidPlan = new PlanXml(
                "plan1", "invalid-date", "2024-12-15T23:00:00",
                "2024-12-01T00:00:00", "2024-12-14T23:59:59", false, List.of()
        );

        BasePlanXml basePlan = new BasePlanXml("base1", "online", "Test Event", "org1", List.of(invalidPlan));

        // When
        List<Event> events = eventMapper.mapToOnlineEvents(List.of(basePlan));

        // Then
        assertThat(events).isEmpty(); // Invalid events should be filtered out
    }

    @Test
    void shouldHandleEmptyZonesList() {
        // Given
        PlanXml planWithNoZones = new PlanXml(
                "plan1", "2024-12-15T20:00:00", "2024-12-15T23:00:00",
                "2024-12-01T00:00:00", "2024-12-14T23:59:59", false, List.of()
        );

        BasePlanXml basePlan = new BasePlanXml("base1", "online", "Test Event", "org1", List.of(planWithNoZones));

        // When
        List<Event> events = eventMapper.mapToOnlineEvents(List.of(basePlan));

        // Then
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.minPrice()).isEqualTo(BigDecimal.ZERO);
        assertThat(event.maxPrice()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void shouldMapMultiplePlansFromSameBasePlan() {
        // Given
        List<ZoneXml> zones = List.of(
                new ZoneXml("1", 100, 50.0, "VIP", false)
        );

        PlanXml plan1 = new PlanXml(
                "plan1", "2024-12-15T20:00:00", "2024-12-15T23:00:00",
                "2024-12-01T00:00:00", "2024-12-14T23:59:59", false, zones
        );

        PlanXml plan2 = new PlanXml(
                "plan2", "2024-12-16T20:00:00", "2024-12-16T23:00:00",
                "2024-12-01T00:00:00", "2024-12-15T23:59:59", false, zones
        );

        BasePlanXml basePlan = new BasePlanXml("base1", "online", "Concert Series", "org1", List.of(plan1, plan2));

        // When
        List<Event> events = eventMapper.mapToOnlineEvents(List.of(basePlan));

        // Then
        assertThat(events).hasSize(2);
        assertThat(events.get(0).startDate()).isEqualTo(LocalDate.of(2024, 12, 15));
        assertThat(events.get(1).startDate()).isEqualTo(LocalDate.of(2024, 12, 16));
    }

    @Test
    void shouldGenerateUniqueIds() {
        // Given
        List<BasePlanXml> basePlans = List.of(
                createBasePlan("online", "Concert A"),
                createBasePlan("online", "Concert B")
        );

        // When
        List<Event> events = eventMapper.mapToOnlineEvents(basePlans);

        // Then
        assertThat(events).hasSize(2);
        assertThat(events.get(0).id()).isNotEqualTo(events.get(1).id());
    }

    @Test
    void shouldHandleZeroCapacityZones() {
        // Given
        List<ZoneXml> zones = List.of(
                new ZoneXml("1", 0, 50.0, "Sold Out Zone", false),
                new ZoneXml("2", 0, 25.0, "Another Sold Out", false)
        );

        PlanXml plan = new PlanXml(
                "plan1", "2024-12-15T20:00:00", "2024-12-15T23:00:00",
                "2024-12-01T00:00:00", "2024-12-14T23:59:59", false, zones
        );

        BasePlanXml basePlan = new BasePlanXml("base1", "online", "Sold Out Event", "org1", List.of(plan));

        // When
        List<Event> events = eventMapper.mapToOnlineEvents(List.of(basePlan));

        // Then
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.minPrice()).isEqualTo(BigDecimal.ZERO);
        assertThat(event.maxPrice()).isEqualTo(BigDecimal.ZERO);
    }

    private BasePlanXml createBasePlan(String sellMode, String title) {
        List<ZoneXml> zones = List.of(
                new ZoneXml("1", 100, 50.0, "Zone1", false)
        );

        PlanXml plan = new PlanXml(
                "plan1", "2024-12-15T20:00:00", "2024-12-15T23:00:00",
                "2024-12-01T00:00:00", "2024-12-14T23:59:59", false, zones
        );

        return new BasePlanXml("base1", sellMode, title, "org1", List.of(plan));
    }
}
