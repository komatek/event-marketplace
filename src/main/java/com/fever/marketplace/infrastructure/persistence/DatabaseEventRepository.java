package com.fever.marketplace.infrastructure.persistence;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.domain.port.out.EventRepository;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
@Qualifier("databaseEventRepository")
public class DatabaseEventRepository implements EventRepository {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseEventRepository.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Event> findByDateRange(LocalDateTime startsAt, LocalDateTime endsAt) {
        String sql = """
            SELECT id, title, start_date, start_time, end_date, end_time, min_price, max_price
            FROM events 
            WHERE (start_date + start_time) <= ?
              AND (end_date + end_time) >= ?
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
                    endsAt, startsAt);

        } catch (DataAccessException e) {
            logger.error("Database error while finding events by date range", e);
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional
    public void addNewEvents(List<Event> events) {
        if (events.isEmpty()) {
            return;
        }

        // Use event_hash as the primary conflict resolution key
        // DON'T update the UUID - it should be immutable once set
        String sql = """
            INSERT INTO events (
                id, title, start_date, start_time, end_date, end_time, 
                min_price, max_price, event_hash, created_at, updated_at
            ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (event_hash) DO UPDATE SET
                title = EXCLUDED.title,
                start_date = EXCLUDED.start_date,
                start_time = EXCLUDED.start_time,
                end_date = EXCLUDED.end_date,
                end_time = EXCLUDED.end_time,
                min_price = EXCLUDED.min_price,
                max_price = EXCLUDED.max_price,
                updated_at = CURRENT_TIMESTAMP
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

            int totalOperations = (int) Arrays.stream(results).filter(r -> r > 0).count();

            logger.info("Processed {} events (inserted new or updated existing)", totalOperations);

        } catch (DataAccessException e) {
            logger.error("Error processing events in database", e);
            throw new RuntimeException("Failed to process events", e);
        }
    }

    /**
     * Generate consistent event hash for conflict detection
     * This determines if an event is "the same" business-wise
     */
    private String generateEventHash(Event event) {
        String businessKey = event.title() + "_" +
                event.startDate() + "_" +
                event.startTime() + "_" +
                event.endDate() + "_" +
                event.endTime();

        return String.valueOf(businessKey.hashCode());
    }
}
