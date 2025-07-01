package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.EventRepository;
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
@Repository("databaseEventRepository")
public class DatabaseEventRepository implements EventRepository {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseEventRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Event> findByDateRange(LocalDateTime startsAt, LocalDateTime endsAt) {
        String sql = """
            SELECT id, title, start_date, start_time, end_date, end_time, min_price, max_price
            FROM events 
            WHERE (start_date > ? OR (start_date = ? AND start_time >= ?))
              AND (end_date < ? OR (end_date = ? AND end_time <= ?))
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
                    startsAt.toLocalDate(),
                    startsAt.toLocalDate(),
                    startsAt.toLocalTime(),
                    endsAt.toLocalDate(),
                    endsAt.toLocalDate(),
                    endsAt.toLocalTime());

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

        // Use INSERT IGNORE to handle duplicates gracefully
        // MySQL will skip records that violate unique constraints
        String sql = """
            INSERT IGNORE INTO events (
                id, title, start_date, start_time, end_date, end_time, 
                min_price, max_price, event_hash, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            """;

        try {
            List<Object[]> batch = events.stream()
                    .map(event -> new Object[] {
                            event.id().toString(),
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

            // Log statistics for monitoring
            if (addedCount > 0) {
                logger.info("Added {} new events to permanent storage", addedCount);
            }

            if (skippedCount > 0) {
                logger.debug("Skipped {} duplicate events (already in database)", skippedCount);
            }

        } catch (DataAccessException e) {
            logger.error("Error adding new events to database", e);
            throw new RuntimeException("Failed to add new events", e);
        }
    }

    private String generateEventHash(Event event) {
        return (event.title() + "_" + event.startDate() + "_" + event.startTime()).hashCode() + "";
    }
}
