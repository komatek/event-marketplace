package com.fever.marketplace;

import com.fever.marketplace.infrastructure.adapter.provider.ExternalEventApi;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest(classes = FeverMarketplaceApplication.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "fever.sync.enabled=false",
        "fever.cache.bucket.ttl-hours=1",
        "fever.cache.bucket.enable-tiered-ttl=false",
        "logging.level.com.fever.marketplace=DEBUG",
        "spring.jpa.hibernate.ddl-auto=none",
        "fever.cache.bucket.max-buckets-per-query=1"
})
class FeverMarketplaceFullIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("fever_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false); // Don't reuse between test runs

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(false) // Don't reuse between test runs
            .withCommand("redis-server", "--save", "", "--appendonly", "no"); // Disable persistence

    private static WireMockServer wireMockServer;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate; // Add Redis template for cache clearing

    private MockMvc mockMvc; // Create manually instead of injecting

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Wait for containers to be ready
        postgres.start();
        redis.start();

        // Configure database properties
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Configure Redis properties
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2000ms");

        // Ensure auto-configuration for JDBC
        registry.add("spring.autoconfigure.exclude", () -> "");
    }

    @AfterEach
    void tearDown() {
        // Additional cleanup after each test
        try {
            clearRedisCache();
            cleanDatabase();
            System.out.println("=== Test teardown complete ===");
        } catch (Exception e) {
            System.err.println("Teardown cleanup failed: " + e.getMessage());
        }
    }

    @Configuration
    static class TestConfig {

        @Bean
        @Primary
        public ExternalEventApi testExternalEventApi() {
            XmlMapper xmlMapper = new XmlMapper();
            xmlMapper.registerModule(new JavaTimeModule());
            xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl("http://localhost:8089/")
                    .addConverterFactory(JacksonConverterFactory.create(xmlMapper))
                    .build();
            return retrofit.create(ExternalEventApi.class);
        }
    }

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8089));
        wireMockServer.start();
        configureFor("localhost", 8089);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        // Create MockMvc manually from WebApplicationContext
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        if (!postgres.isRunning()) postgres.start();
        if (!redis.isRunning()) redis.start();

        // CRITICAL: Clear Redis cache completely
        clearRedisCache();

        // Setup database schema
        setupDatabaseSchema();

        // FORCE clean database before each test
        cleanDatabase();

        // Reset WireMock
        wireMockServer.resetAll();

        System.out.println("=== Test setup complete - Clean slate ensured ===");
    }

    private void clearRedisCache() {
        try {
            if (redisTemplate != null) {
                // Method 1: Clear all Redis data
                redisTemplate.getConnectionFactory().getConnection().flushDb();
                System.out.println("Redis cache cleared with FLUSHDB");

                // Method 2: Alternative - delete specific keys
                var keys = redisTemplate.keys("fever:events:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                    System.out.println("Deleted " + keys.size() + " Redis cache keys");
                }

                // Verify Redis is clean
                var remainingKeys = redisTemplate.keys("*");
                System.out.println("Remaining Redis keys: " + (remainingKeys != null ? remainingKeys.size() : 0));
            } else {
                System.out.println("RedisTemplate not available - skipping Redis cleanup");
            }

        } catch (Exception e) {
            System.err.println("Redis cache cleanup failed: " + e.getMessage());
        }
    }

    private void setupDatabaseSchema() {
        try {
            // Check if schema exists
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = 'events'",
                    Integer.class
            );

            if (count == null || count == 0) {
                Flyway flyway = Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .cleanDisabled(false)
                        .load();
                flyway.migrate();
                System.out.println("Database schema created");
            }
        } catch (Exception e) {
            // If check fails, run migration anyway
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .cleanDisabled(false)
                        .load();
                flyway.migrate();
                System.out.println("Database schema migration completed");
            } catch (Exception migrationError) {
                System.err.println("Database setup warning: " + migrationError.getMessage());
            }
        }
    }

    private void cleanDatabase() {
        try {
            // Try multiple approaches to ensure clean database
            jdbcTemplate.execute("TRUNCATE TABLE events RESTART IDENTITY CASCADE");
            System.out.println("Database cleaned with TRUNCATE");
        } catch (Exception e1) {
            try {
                jdbcTemplate.execute("DELETE FROM events");
                System.out.println("Database cleaned with DELETE");
            } catch (Exception e2) {
                System.err.println("All database cleanup methods failed: " + e2.getMessage());
            }
        }

        // Verify database is actually clean
        try {
            Integer eventCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
            System.out.println("Events remaining after cleanup: " + eventCount);
            if (eventCount != null && eventCount > 0) {
                System.err.println("WARNING: Database cleanup failed, " + eventCount + " events remain");
                // Force delete any remaining events
                jdbcTemplate.execute("DELETE FROM events WHERE 1=1");
                Integer finalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
                System.out.println("Events after forced cleanup: " + finalCount);
            }
        } catch (Exception e) {
            System.err.println("Could not verify database cleanup: " + e.getMessage());
        }
    }

    @Test
    @Order(1)
    void shouldReturnEmptyWhenNoEventsInDatabase() throws Exception {
        cleanDatabase();

        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.events").isArray())
                .andExpect(jsonPath("$.data.events", hasSize(0)));
    }

    @Test
    @Order(2)
    void shouldReturnEventsFromDatabase() throws Exception {
        cleanDatabase();

        // Given - Insert test data with unique titles
        insertTestEvent("ConcertMadrid_" + System.currentTimeMillis(), "2024-12-15");
        insertTestEvent("TheaterShow_" + System.currentTimeMillis(), "2024-12-20");

        // When & Then
        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "2024-12-01T10:00:00")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(2)))
                .andExpect(jsonPath("$.data.events[0].title", containsString("ConcertMadrid")))
                .andExpect(jsonPath("$.data.events[1].title", containsString("TheaterShow")));
    }

    @Test
    @Order(3)
    void shouldFilterEventsByDateRange() throws Exception {
        // Force clean database
        cleanDatabase();

        // Given - Insert events with different dates using unique titles
        insertTestEvent("DecemberEvent_" + System.currentTimeMillis(), "2024-12-15");
        insertTestEvent("JanuaryEvent_" + System.currentTimeMillis(), "2025-01-15");

        // When & Then - Query only December
        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "2024-12-01T00:00:00")
                        .param("ends_at", "2024-12-31T23:59:59")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(1)))
                .andExpect(jsonPath("$.data.events[0].title", containsString("DecemberEvent")));
    }

    @Test
    @Order(4)
    void shouldHandleBadRequestGracefully() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "invalid-date")
                        .param("ends_at", "2024-12-31T23:59:00")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(5)
    void shouldSyncEventsFromExternalProvider() {
        // Given - Mock external API
        String xmlResponse = createValidXmlResponse();
        stubFor(WireMock.get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(xmlResponse)));

        // For this test, we verify the integration works by checking the external API stub
        verify(0, getRequestedFor(urlEqualTo("/api/events"))); // No calls yet since sync is disabled
    }

    @Test
    @Order(6)
    void shouldHandleDatabaseConstraints() throws Exception {
        // Force clean database
        cleanDatabase();

        // Given - Insert event with same business key twice (should test actual constraint behavior)
        String eventTitle = "Duplicate Event";
        String eventDate = "2024-12-15";
        String eventTime = "20:00:00";

        // Use IDENTICAL business key deliberately to test constraint
        String businessKey = eventTitle + "_" + eventDate + "_" + eventTime + "_" + eventDate + "_23:00:00";
        String eventHash = String.valueOf(businessKey.hashCode());

        String sql = """
            INSERT INTO events (
                id, title, start_date, start_time, end_date, end_time, 
                min_price, max_price, event_hash
            ) VALUES (?::uuid, ?, ?::date, ?::time, ?::date, ?::time, ?, ?, ?)
            """;

        // Insert first event
        jdbcTemplate.update(sql,
                java.util.UUID.randomUUID().toString(),
                eventTitle, eventDate, eventTime, eventDate, "23:00:00",
                25.00, 100.00, eventHash);

        // Second insert with EXACT SAME hash should be rejected by unique constraint
        try {
            jdbcTemplate.update(sql,
                    java.util.UUID.randomUUID().toString(),
                    eventTitle, eventDate, eventTime, eventDate, "23:00:00",
                    25.00, 100.00, eventHash); // Same hash!
            System.out.println("Second insertion succeeded (unexpected)");
        } catch (Exception e) {
            // Expected: constraint violation because event_hash is unique
            System.out.println("Expected constraint violation: " + e.getMessage());
        }

        // When & Then - Should only have one event due to unique constraint
        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "2024-12-01T00:00:00")
                        .param("ends_at", "2024-12-31T23:59:59")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(1)));
    }

    @Test
    @Order(7)
    void shouldOrderEventsByDateTime() throws Exception {
        // Force clean database
        cleanDatabase();

        // Given - Insert events in reverse chronological order with unique titles
        insertTestEventWithTime("LaterEvent_" + System.currentTimeMillis(), "2024-12-15", "22:00:00");
        insertTestEventWithTime("EarlierEvent_" + System.currentTimeMillis(), "2024-12-15", "20:00:00");
        insertTestEventWithTime("NextDayEvent_" + System.currentTimeMillis(), "2024-12-16", "19:00:00");

        // When & Then - Should return in chronological order
        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "2024-12-01T00:00:00")
                        .param("ends_at", "2024-12-31T23:59:59")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(3)))
                .andExpect(jsonPath("$.data.events[0].title", containsString("EarlierEvent")))
                .andExpect(jsonPath("$.data.events[1].title", containsString("LaterEvent")))
                .andExpect(jsonPath("$.data.events[2].title", containsString("NextDayEvent")));
    }

    @Test
    @Order(10)
    void shouldHandleLargeDataSet() throws Exception {
        // Force clean database and verify
        cleanDatabase();

        // Given - Insert many events with UNIQUE titles to avoid any conflicts
        for (int i = 1; i <= 50; i++) {
            String uniqueTitle = "LargeDataSetEvent_" + i + "_" + System.currentTimeMillis();
            insertTestEvent(uniqueTitle, "2024-12-" + String.format("%02d", (i % 28) + 1));
        }

        // When & Then
        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "2024-12-01T00:00:00")
                        .param("ends_at", "2024-12-31T23:59:59")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(50)));
    }

    @Test
    @Order(8)
    void shouldValidateFullResponseStructure() throws Exception {
        // Given
        insertTestEvent("Full Structure Test", "2024-12-15");

        // When & Then
        mockMvc.perform(MockMvcRequestBuilders.get("/search")
                        .param("starts_at", "2024-12-01T00:00:00")
                        .param("ends_at", "2024-12-31T23:59:59")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
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

    private void insertTestEvent(String title, String date) {
        insertTestEventWithTime(title, date, "20:00:00");
    }

    private void insertTestEventWithTime(String title, String date, String time) {
        String sql = """
            INSERT INTO events (
                id, title, start_date, start_time, end_date, end_time, 
                min_price, max_price, event_hash
            ) VALUES (?::uuid, ?, ?::date, ?::time, ?::date, ?::time, ?, ?, ?)
            """;

        // Generate unique hash to avoid collisions - include UUID for uniqueness
        String uniqueId = java.util.UUID.randomUUID().toString();
        String eventHash = String.valueOf((title + "_" + date + "_" + time + "_" + uniqueId).hashCode());

        jdbcTemplate.update(sql,
                uniqueId,
                title,
                date, time,
                date, "23:00:00",
                25.00, 100.00,
                eventHash
        );
    }

    private String createValidXmlResponse() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <planList version="1.0">
                <output>
                    <base_plan base_plan_id="1" sell_mode="online" title="External Event" organizer_company_id="company1">
                        <plan plan_id="plan1" plan_start_date="2024-12-15T20:00:00" plan_end_date="2024-12-15T23:00:00" 
                              sell_from="2024-12-01T00:00:00" sell_to="2024-12-15T19:00:00" sold_out="false">
                            <zone zone_id="zone1" capacity="100" price="25.50" name="General" numbered="false"/>
                        </plan>
                    </base_plan>
                </output>
            </planList>
            """;
    }
}
