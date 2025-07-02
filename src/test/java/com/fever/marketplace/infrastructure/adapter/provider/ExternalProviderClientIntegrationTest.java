package com.fever.marketplace.infrastructure.adapter.provider;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.adapter.mapper.EventMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "fever.sync.enabled=false",
        "logging.level.com.fever.marketplace=DEBUG"
})
class ExternalProviderClientIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("fever_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static WireMockServer wireMockServer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @TestConfiguration
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
    static void setupServer() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8089));
        wireMockServer.start();
        configureFor("localhost", 8089);
    }

    @AfterAll
    static void stopServer() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    @Test
    void fetchOnlineEvents_shouldReturnMappedEvents() throws Exception {
        // Given
        String xmlResponse = """
            <?xml version="1.0" encoding="UTF-8"?>
            <planList version="1.0">
              <output>
                <base_plan base_plan_id="1" sell_mode="online" title="Test Event" organizer_company_id="company1">
                  <plan plan_id="p1" plan_start_date="2024-12-01T20:00:00" plan_end_date="2024-12-01T23:00:00"
                        sell_from="2024-11-01T00:00:00" sell_to="2024-12-01T19:00:00" sold_out="false">
                    <zone zone_id="z1" capacity="100" price="50.0" name="General" numbered="false"/>
                  </plan>
                </base_plan>
              </output>
            </planList>
            """;

        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(xmlResponse)));

        // When
        ExternalProviderClient client = new ExternalProviderClient(
                testExternalEventApi(),
                new EventMapper()
        );

        CompletableFuture<List<Event>> result = client.fetchOnlineEvents();
        List<Event> events = result.get(10, TimeUnit.SECONDS);

        // Then
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).title()).isEqualTo("Test Event");

        // Verify WireMock was called
        verify(1, getRequestedFor(urlEqualTo("/api/events")));
    }

    private ExternalEventApi testExternalEventApi() {
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
