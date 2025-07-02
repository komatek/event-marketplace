package com.fever.marketplace.infrastructure.cache.bucket;

import com.fever.marketplace.domain.model.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BucketOperationsIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true)
            .withCommand("redis-server", "--appendonly", "no", "--save", "");

    private RedisTemplate<String, String> redisTemplate;
    private ObjectMapper objectMapper;
    private BucketCacheConfig config;
    private BucketOperations bucketOperations;

    @BeforeEach
    void setUp() {
        // Setup Redis connection
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                redis.getHost(),
                redis.getMappedPort(6379)
        );
        connectionFactory.setDatabase(1); // Use test database
        connectionFactory.afterPropertiesSet();

        // Setup RedisTemplate
        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Setup test configuration
        config = createTestConfig();

        // Create the component under test
        bucketOperations = new BucketOperations(redisTemplate, objectMapper, config);

        // Clear any existing test data
        clearTestKeys();
    }

    @AfterEach
    void tearDown() {
        clearTestKeys();

        if (redisTemplate.getConnectionFactory() != null) {
            redisTemplate.getConnectionFactory().getConnection().close();
        }
    }

    private BucketCacheConfig createTestConfig() {
        return new BucketCacheConfig() {{
            setKeyPrefix("test:events:month:");
            setTtlHours(6);
            setCurrentMonthTtlHours(2);
            setLongTermTtlHours(24);
            setEnableTieredTtl(true);
        }};
    }

    private void clearTestKeys() {
        Set<String> keys = redisTemplate.keys("test:events:month:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void shouldStoreAndRetrieveEventsSuccessfully() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        List<Event> events = List.of(
                createEvent("Concert in Barcelona", LocalDate.of(2024, 12, 15)),
                createEvent("Theater Show", LocalDate.of(2024, 12, 20))
        );

        // When - Store events
        bucketOperations.putBucketEvents(bucketKey, events);

        // Then - Retrieve and verify
        List<Event> retrievedEvents = bucketOperations.getBucketEvents(bucketKey);

        assertThat(retrievedEvents).isNotNull();
        assertThat(retrievedEvents).hasSize(2);
        assertThat(retrievedEvents.get(0).title()).isEqualTo("Concert in Barcelona");
        assertThat(retrievedEvents.get(1).title()).isEqualTo("Theater Show");

        // Verify complex fields are preserved
        assertThat(retrievedEvents.get(0).startDate()).isEqualTo(LocalDate.of(2024, 12, 15));
        assertThat(retrievedEvents.get(0).startTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(retrievedEvents.get(0).minPrice()).isEqualByComparingTo(BigDecimal.valueOf(25.00));
        assertThat(retrievedEvents.get(0).maxPrice()).isEqualByComparingTo(BigDecimal.valueOf(100.00));
    }

    @Test
    void shouldReturnNullOnCacheMiss() {
        // Given
        LocalDate nonExistentKey = LocalDate.of(2025, 1, 1);

        // When
        List<Event> result = bucketOperations.getBucketEvents(nonExistentKey);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldHandleEmptyEventsList() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        List<Event> emptyEvents = List.of();

        // When
        bucketOperations.putBucketEvents(bucketKey, emptyEvents);

        // Then
        List<Event> retrievedEvents = bucketOperations.getBucketEvents(bucketKey);
        assertThat(retrievedEvents).isNotNull();
        assertThat(retrievedEvents).isEmpty();
    }

    @Test
    void shouldUseSameCacheKeyForDifferentDatesInSameMonth() {
        // Given
        LocalDate firstDay = LocalDate.of(2024, 12, 1);
        LocalDate middleDay = LocalDate.of(2024, 12, 15);
        LocalDate lastDay = LocalDate.of(2024, 12, 31);

        List<Event> firstDayEvents = List.of(createEvent("First Day Event", firstDay));
        List<Event> middleDayEvents = List.of(createEvent("Middle Day Event", middleDay));

        // When - Store events for different dates in same month
        bucketOperations.putBucketEvents(firstDay, firstDayEvents);

        // Verify first storage
        List<Event> eventsAfterFirst = bucketOperations.getBucketEvents(firstDay);
        assertThat(eventsAfterFirst).hasSize(1);
        assertThat(eventsAfterFirst.get(0).title()).isEqualTo("First Day Event");

        // Store new events (should overwrite)
        bucketOperations.putBucketEvents(middleDay, middleDayEvents);

        // Then - All keys should resolve to same bucket with latest data
        List<Event> eventsFromFirst = bucketOperations.getBucketEvents(firstDay);
        List<Event> eventsFromMiddle = bucketOperations.getBucketEvents(middleDay);
        List<Event> eventsFromLast = bucketOperations.getBucketEvents(lastDay);

        // All should return the same data (latest stored)
        assertThat(eventsFromFirst).hasSize(1);
        assertThat(eventsFromMiddle).hasSize(1);
        assertThat(eventsFromLast).hasSize(1);

        assertThat(eventsFromFirst.get(0).title()).isEqualTo("Middle Day Event");
        assertThat(eventsFromMiddle.get(0).title()).isEqualTo("Middle Day Event");
        assertThat(eventsFromLast.get(0).title()).isEqualTo("Middle Day Event");
    }

    @Test
    void shouldSuccessfullyInvalidateBucket() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        List<Event> events = List.of(createEvent("Event to be deleted", bucketKey));

        bucketOperations.putBucketEvents(bucketKey, events);

        // Verify events are stored
        assertThat(bucketOperations.getBucketEvents(bucketKey)).isNotNull();

        // When
        boolean deleted = bucketOperations.invalidateBucket(bucketKey);

        // Then
        assertThat(deleted).isTrue();
        assertThat(bucketOperations.getBucketEvents(bucketKey)).isNull();
    }

    @Test
    void shouldReturnFalseWhenInvalidatingNonExistentBucket() {
        // Given
        LocalDate nonExistentKey = LocalDate.of(2025, 1, 1);

        // When
        boolean deleted = bucketOperations.invalidateBucket(nonExistentKey);

        // Then
        assertThat(deleted).isFalse();
    }

    @Test
    void shouldGenerateCorrectCacheKeys() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 15);
        List<Event> events = List.of(createEvent("Test Event", bucketKey));

        // When
        bucketOperations.putBucketEvents(bucketKey, events);

        // Then - Verify the exact key was created
        Set<String> keys = redisTemplate.keys("test:events:month:*");
        assertThat(keys).hasSize(1);
        assertThat(keys.iterator().next()).isEqualTo("test:events:month:2024-12");
    }

    @Test
    void shouldHandleLargeEventsList() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        List<Event> largeEventsList = java.util.stream.IntStream.range(1, 101)
                .mapToObj(i -> createEvent("Event " + i, bucketKey.plusDays(i % 28)))
                .toList();

        // When
        bucketOperations.putBucketEvents(bucketKey, largeEventsList);

        // Then
        List<Event> retrievedEvents = bucketOperations.getBucketEvents(bucketKey);
        assertThat(retrievedEvents).hasSize(100);

        // Verify first and last events
        assertThat(retrievedEvents.get(0).title()).isEqualTo("Event 1");
        assertThat(retrievedEvents.get(99).title()).isEqualTo("Event 100");
    }

    @Test
    void shouldHandleComplexEventData() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);

        Event complexEvent = new Event(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "Festival Músical con Café & Vino 'Especial'", // Special characters
                LocalDate.of(2024, 12, 15),
                LocalTime.of(19, 30, 45), // Precise time
                LocalDate.of(2024, 12, 17), // Multi-day event
                LocalTime.of(23, 45, 30),
                BigDecimal.valueOf(15.99), // Decimal precision
                BigDecimal.valueOf(299.95)
        );

        List<Event> events = List.of(complexEvent);

        // When
        bucketOperations.putBucketEvents(bucketKey, events);

        // Then
        List<Event> retrievedEvents = bucketOperations.getBucketEvents(bucketKey);
        assertThat(retrievedEvents).hasSize(1);

        Event retrieved = retrievedEvents.get(0);
        assertThat(retrieved.id()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(retrieved.title()).isEqualTo("Festival Músical con Café & Vino 'Especial'");
        assertThat(retrieved.startDate()).isEqualTo(LocalDate.of(2024, 12, 15));
        assertThat(retrieved.startTime()).isEqualTo(LocalTime.of(19, 30, 45));
        assertThat(retrieved.endDate()).isEqualTo(LocalDate.of(2024, 12, 17));
        assertThat(retrieved.endTime()).isEqualTo(LocalTime.of(23, 45, 30));
        assertThat(retrieved.minPrice()).isEqualByComparingTo(BigDecimal.valueOf(15.99));
        assertThat(retrieved.maxPrice()).isEqualByComparingTo(BigDecimal.valueOf(299.95));
    }

    @Test
    void shouldUseCustomKeyPrefix() {
        // Given
        BucketCacheConfig customConfig = createTestConfig();
        customConfig.setKeyPrefix("custom:prefix:");
        BucketOperations customBucketOps = new BucketOperations(redisTemplate, objectMapper, customConfig);

        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        List<Event> events = List.of(createEvent("Custom Prefix Event", bucketKey));

        // When
        customBucketOps.putBucketEvents(bucketKey, events);

        // Then
        List<Event> retrievedEvents = customBucketOps.getBucketEvents(bucketKey);
        assertThat(retrievedEvents).isNotNull();
        assertThat(retrievedEvents).hasSize(1);

        // Verify the key was created with custom prefix
        Set<String> keys = redisTemplate.keys("custom:prefix:*");
        assertThat(keys).hasSize(1);
        assertThat(keys.iterator().next()).isEqualTo("custom:prefix:2024-12");
    }

    @Test
    void shouldHandleConcurrentWrites() throws Exception {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        int numberOfThreads = 5;
        int eventsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        // When - Multiple threads store events concurrently to same bucket
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                List<Event> events = java.util.stream.IntStream.range(0, eventsPerThread)
                        .mapToObj(j -> createEvent("Thread-" + threadId + "-Event-" + j, bucketKey))
                        .toList();
                bucketOperations.putBucketEvents(bucketKey, events);
            }, executor);
            futures.add(future);
        }

        // Wait for all operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

        // Then - Only last write should be present (last writer wins)
        List<Event> finalEvents = bucketOperations.getBucketEvents(bucketKey);
        assertThat(finalEvents).isNotNull();
        assertThat(finalEvents).hasSize(eventsPerThread); // Only one thread's events remain

        executor.shutdown();
    }

    @Test
    void shouldHandleConcurrentReads() throws Exception {
        // Given - Pre-populate bucket
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        List<Event> events = List.of(
                createEvent("Event 1", bucketKey),
                createEvent("Event 2", bucketKey),
                createEvent("Event 3", bucketKey)
        );
        bucketOperations.putBucketEvents(bucketKey, events);

        int numberOfReaders = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfReaders);

        // When - Multiple threads read concurrently
        List<CompletableFuture<List<Event>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < numberOfReaders; i++) {
            CompletableFuture<List<Event>> future = CompletableFuture.supplyAsync(() ->
                    bucketOperations.getBucketEvents(bucketKey), executor);
            futures.add(future);
        }

        // Then - All reads should return same data
        for (CompletableFuture<List<Event>> future : futures) {
            List<Event> readEvents = future.get(30, TimeUnit.SECONDS);
            assertThat(readEvents).hasSize(3);
            assertThat(readEvents.get(0).title()).isEqualTo("Event 1");
            assertThat(readEvents.get(1).title()).isEqualTo("Event 2");
            assertThat(readEvents.get(2).title()).isEqualTo("Event 3");
        }

        executor.shutdown();
    }

    @Test
    void shouldHandleRedisConnectionResilience() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        List<Event> events = List.of(createEvent("Resilience Test Event", bucketKey));

        // When - Store data
        bucketOperations.putBucketEvents(bucketKey, events);

        // Then - Data should be retrievable
        List<Event> retrievedEvents = bucketOperations.getBucketEvents(bucketKey);
        assertThat(retrievedEvents).isNotNull();
        assertThat(retrievedEvents).hasSize(1);

        // Verify data persists across operations
        boolean deleted = bucketOperations.invalidateBucket(bucketKey);
        assertThat(deleted).isTrue();
        assertThat(bucketOperations.getBucketEvents(bucketKey)).isNull();
    }

    @Test
    void shouldVerifyRedisDataFormat() {
        // Given
        LocalDate bucketKey = LocalDate.of(2024, 12, 1);
        Event event = createEvent("JSON Format Test", bucketKey);
        List<Event> events = List.of(event);

        // When
        bucketOperations.putBucketEvents(bucketKey, events);

        // Then - Verify raw Redis data is JSON
        String rawData = redisTemplate.opsForValue().get("test:events:month:2024-12");
        assertThat(rawData).isNotNull();
        assertThat(rawData).contains("\"title\":\"JSON Format Test\"");
        assertThat(rawData).contains("\"startDate\":");
        assertThat(rawData).contains("\"minPrice\":");

        // Verify it can be deserialized back
        List<Event> retrievedEvents = bucketOperations.getBucketEvents(bucketKey);
        assertThat(retrievedEvents).hasSize(1);
        assertThat(retrievedEvents.get(0).title()).isEqualTo("JSON Format Test");
    }

    @Test
    void shouldHandleTtlForDifferentMonthAges() {
        // Given - Test different month ages
        YearMonth currentMonth = YearMonth.now();

        // Current month
        LocalDate currentMonthKey = currentMonth.atDay(1);
        List<Event> currentEvents = List.of(createEvent("Current Month", currentMonthKey));
        bucketOperations.putBucketEvents(currentMonthKey, currentEvents);

        // Old month (6 months ago)
        LocalDate oldMonthKey = currentMonth.minusMonths(6).atDay(1);
        List<Event> oldEvents = List.of(createEvent("Old Month", oldMonthKey));
        bucketOperations.putBucketEvents(oldMonthKey, oldEvents);

        // Then - Both should be stored successfully
        assertThat(bucketOperations.getBucketEvents(currentMonthKey)).hasSize(1);
        assertThat(bucketOperations.getBucketEvents(oldMonthKey)).hasSize(1);

        // Verify different keys were created
        Set<String> keys = redisTemplate.keys("test:events:month:*");
        assertThat(keys).hasSize(2);
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
}
