package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Testcontainers
class DatabaseEventRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("fever_test")
                    .withUsername("test")
                    .withPassword("test");

    private static DataSource dataSource;
    private static DatabaseEventRepository repository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setup() {
        waitUntilPostgresIsReady(postgres); // ✅ Important - from working example

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);

        jdbcTemplate = new JdbcTemplate(dataSource);

        // Use Flyway to run migrations (V1__Create_events_table.sql)
        Flyway.configure().dataSource(dataSource).load().migrate();

        repository = new DatabaseEventRepository(jdbcTemplate);
    }

    private static void waitUntilPostgresIsReady(PostgreSQLContainer<?> container) {
        int retries = 10;
        while (retries-- > 0) {
            try (Connection conn = DriverManager.getConnection(
                    container.getJdbcUrl(),
                    container.getUsername(),
                    container.getPassword())) {
                if (conn.isValid(2)) return;
            } catch (Exception e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
            }
        }
        throw new IllegalStateException("PostgreSQL not ready after retries");
    }

    @BeforeEach
    void cleanup() throws SQLException {
        // Clean data between tests while preserving schema
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM events"); // Use DELETE instead of TRUNCATE to avoid restart identity issues
        }
    }

    @Test
    void shouldFindEventsByDateRange() {
        // Given - Insert test data directly into database
        insertEventDirectly(createEventWithDate(LocalDate.of(2024, 12, 15)));
        insertEventDirectly(createEventWithDate(LocalDate.of(2024, 12, 20)));
        insertEventDirectly(createEventWithDate(LocalDate.of(2025, 1, 5))); // Outside range

        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        // When
        List<Event> events = repository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(events).hasSize(2);
        assertThat(events).extracting(Event::startDate)
                .containsExactly(LocalDate.of(2024, 12, 15), LocalDate.of(2024, 12, 20));
    }

    @Test
    void shouldReturnEventsOrderedByDateTime() {
        // Given - Insert test data directly
        Event laterEvent = createEventWithDateTime(LocalDate.of(2024, 12, 15), LocalTime.of(22, 0));
        Event earlierEvent = createEventWithDateTime(LocalDate.of(2024, 12, 15), LocalTime.of(20, 0));
        Event laterDateEvent = createEventWithDateTime(LocalDate.of(2024, 12, 16), LocalTime.of(19, 0));

        insertEventDirectly(laterEvent);
        insertEventDirectly(earlierEvent);
        insertEventDirectly(laterDateEvent);

        LocalDateTime startsAt = LocalDateTime.of(2024, 12, 1, 0, 0);
        LocalDateTime endsAt = LocalDateTime.of(2024, 12, 31, 23, 59);

        // When
        List<Event> events = repository.findByDateRange(startsAt, endsAt);

        // Then
        assertThat(events).hasSize(3);
        assertThat(events.get(0).startTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(events.get(1).startTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(events.get(2).startDate()).isEqualTo(LocalDate.of(2024, 12, 16));
    }

    @Test
    void shouldAddNewEventsAndHandleDuplicates() {
        // Given
        Event event1 = createEventWithTitle("Concert A");
        Event event2 = createEventWithTitle("Concert B");
        Event duplicateEvent1 = createEventWithTitle("Concert A"); // Same business key

        // When
        assertDoesNotThrow(() -> repository.addNewEvents(List.of(event1, event2)));
        assertDoesNotThrow(() -> repository.addNewEvents(List.of(duplicateEvent1))); // Should update, not duplicate

        // Then
        List<Event> allEvents = repository.findByDateRange(
                LocalDateTime.of(2024, 12, 1, 0, 0),
                LocalDateTime.of(2024, 12, 31, 23, 59)
        );

        // Should have unique events based on business key (event_hash)
        long uniqueTitles = allEvents.stream()
                .map(Event::title)
                .distinct()
                .count();

        assertThat(uniqueTitles).isEqualTo(2); // Only Concert A and Concert B
    }

    @Test
    void shouldHandleEventsSpanningMultipleDays() {
        // Given - Insert multi-day event directly
        Event multiDayEvent = new Event(
                UUID.randomUUID(), "Multi-day Festival",
                LocalDate.of(2024, 12, 15), LocalTime.of(18, 0),
                LocalDate.of(2024, 12, 17), LocalTime.of(23, 0),
                BigDecimal.valueOf(50.00), BigDecimal.valueOf(200.00)
        );

        insertEventDirectly(multiDayEvent);

        // When - Query overlapping with start
        List<Event> startOverlap = repository.findByDateRange(
                LocalDateTime.of(2024, 12, 15, 12, 0),
                LocalDateTime.of(2024, 12, 15, 20, 0)
        );

        // When - Query overlapping with end
        List<Event> endOverlap = repository.findByDateRange(
                LocalDateTime.of(2024, 12, 17, 20, 0),
                LocalDateTime.of(2024, 12, 18, 0, 0)
        );

        // Then
        assertThat(startOverlap).hasSize(1);
        assertThat(endOverlap).hasSize(1);
    }

    @Test
    void shouldReturnEmptyListForNoMatches() {
        // Given - Insert event outside query range
        Event event = createEventWithDate(LocalDate.of(2024, 12, 15));
        insertEventDirectly(event);

        // When - Query outside event range
        List<Event> events = repository.findByDateRange(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 31, 23, 59)
        );

        // Then
        assertThat(events).isEmpty();
    }

    @Test
    void shouldHandleLargeEventBatch() {
        // Given
        List<Event> largeEventList = List.of(
                createEventWithTitle("Event 1"),
                createEventWithTitle("Event 2"),
                createEventWithTitle("Event 3"),
                createEventWithTitle("Event 4"),
                createEventWithTitle("Event 5")
        );

        // When
        assertDoesNotThrow(() -> repository.addNewEvents(largeEventList));

        // Then
        List<Event> allEvents = repository.findByDateRange(
                LocalDateTime.of(2024, 12, 1, 0, 0),
                LocalDateTime.of(2024, 12, 31, 23, 59)
        );

        assertThat(allEvents).hasSize(5);
    }

    private void insertEventDirectly(Event event) {
        String sql = """
            INSERT INTO events (
                id, title, start_date, start_time, end_date, end_time, 
                min_price, max_price, event_hash
            ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                event.id().toString(),
                event.title(),
                event.startDate(),
                event.startTime(),
                event.endDate(),
                event.endTime(),
                event.minPrice(),
                event.maxPrice(),
                generateEventHash(event)
        );
    }

    private String generateEventHash(Event event) {
        String businessKey = event.title() + "_" +
                event.startDate() + "_" +
                event.startTime() + "_" +
                event.endDate() + "_" +
                event.endTime();
        return String.valueOf(businessKey.hashCode());
    }

    private Event createEventWithDate(LocalDate date) {
        return new Event(
                UUID.randomUUID(), "Test Event",
                date, LocalTime.of(20, 0),
                date, LocalTime.of(23, 0),
                BigDecimal.valueOf(25.00), BigDecimal.valueOf(100.00)
        );
    }

    private Event createEventWithDateTime(LocalDate date, LocalTime time) {
        return new Event(
                UUID.randomUUID(), "Test Event",
                date, time,
                date, time.plusHours(3),
                BigDecimal.valueOf(25.00), BigDecimal.valueOf(100.00)
        );
    }

    private Event createEventWithTitle(String title) {
        return new Event(
                UUID.randomUUID(), title,
                LocalDate.of(2024, 12, 15), LocalTime.of(20, 0),
                LocalDate.of(2024, 12, 15), LocalTime.of(23, 0),
                BigDecimal.valueOf(25.00), BigDecimal.valueOf(100.00)
        );
    }
}
