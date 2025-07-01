package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Database implementation of EventRepository
 * Pure database operations without caching concerns
 */
@Repository
public class DatabaseEventRepository implements EventRepository {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseEventRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Event> findByDateRange(LocalDateTime startsAt, LocalDateTime endsAt) {
        // Fixed SQL query - events that overlap with the requested range
        String sql = """
            SELECT id, title, start_date, start_time, end_date, end_time, min_price, max_price
            FROM events 
            WHERE (start_date || ' ' || start_time)::timestamp <= ?
              AND (end_date || ' ' || end_time)::timestamp >= ?
            ORDER BY start_date, start_time
            """;

        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> new Event(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("title"),
                            rs.getDate("start_date").toLocalDate(),
                            rs.getTime("start_time").toLocalTime(),
                            rs.getDate("end_date").toLocalDate(),
                            rs.getTime("end_time").toLocalTime(),
                            rs.getBigDecimal("min_price"),
                            rs.getBigDecimal("max_price")
                    ),
                    endsAt, // Events that start before our range ends
                    startsAt); // Events that end after our range starts

        } catch (DataAccessException e) {
            logger.error("Database error while finding events by date range", e);
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional
    public void addNewEvents(List<Event> events) {
        if (events.isEmpty()) {
            logger.debug("No events to add");
            return;
        }

        // Fixed SQL for PostgreSQL - use proper UUID casting
        String sql = """
            INSERT INTO events (
                id, title, start_date, start_time, end_date, end_time, 
                min_price, max_price, event_hash, created_at
            ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (event_hash) DO NOTHING
            """;

        try {
            List<Object[]> batch = events.stream()
                    .map(event -> new Object[] {
                            event.id().toString(), // Convert UUID to string for PostgreSQL casting
                            event.title(),
                            event.startDate(),
                            event.startTime(),
                            event.endDate(),
                            event.endTime(),
                            event.minPrice(),
                            event.maxPrice(),
                            generateEventHash(event)
                    })
                    .toList();

            int[] results = jdbcTemplate.batchUpdate(sql, batch);
            int addedCount = (int) java.util.Arrays.stream(results).filter(r -> r > 0).count();
            int skippedCount = events.size() - addedCount;

            logger.info("Database operation completed: {} new events added, {} duplicates skipped",
                    addedCount, skippedCount);

        } catch (DataAccessException e) {
            logger.error("Error adding new events to database", e);
            throw new RuntimeException("Failed to add new events", e);
        }
    }

    private String generateEventHash(Event event) {
        // Use a more robust hash that includes more fields
        return String.valueOf((event.title() + "_" +
                event.startDate() + "_" +
                event.startTime() + "_" +
                event.endDate() + "_" +
                event.endTime()).hashCode());
    }
}
