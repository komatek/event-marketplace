package com.fever.marketplace.infrastructure.adapter.provider;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.adapter.mapper.EventMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(ExternalProviderClientIntegrationTest.TestConfig.class)
class ExternalProviderClientIntegrationTest {

    static WireMockServer wireMockServer;

    @TestConfiguration
    @ComponentScan(basePackages = {
            "com.fever.marketplace.infrastructure.adapter.mapper"
    })
    static class TestConfig {

        @Bean
        public ExternalEventApi externalEventApi() {
            XmlMapper xmlMapper = new XmlMapper();
            xmlMapper.registerModule(new JavaTimeModule());
            xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl("http://localhost:8089/")
                    .addConverterFactory(JacksonConverterFactory.create(xmlMapper))
                    .build();

            return retrofit.create(ExternalEventApi.class);
        }

        @Bean
        public EventMapper eventMapper() {
            return new EventMapper();
        }

        @Bean
        public ExternalProviderClient externalProviderClient(
                ExternalEventApi externalEventApi,
                EventMapper eventMapper) {
            return new ExternalProviderClient(externalEventApi, eventMapper);
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
                createExternalEventApi(),
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

    @Test
    void fetchOnlineEvents_shouldHandleEmptyResponse() throws Exception {
        // Given
        String emptyXmlResponse = """
            <?xml version="1.0" encoding="UTF-8"?>
            <planList version="1.0">
              <output>
              </output>
            </planList>
            """;

        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(emptyXmlResponse)));

        // When
        ExternalProviderClient client = new ExternalProviderClient(
                createExternalEventApi(),
                new EventMapper()
        );

        CompletableFuture<List<Event>> result = client.fetchOnlineEvents();
        List<Event> events = result.get(10, TimeUnit.SECONDS);

        // Then
        assertThat(events).isEmpty();
        verify(1, getRequestedFor(urlEqualTo("/api/events")));
    }

    @Test
    void fetchOnlineEvents_shouldHandleErrorResponse() throws Exception {
        // Given
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // When
        ExternalProviderClient client = new ExternalProviderClient(
                createExternalEventApi(),
                new EventMapper()
        );

        CompletableFuture<List<Event>> result = client.fetchOnlineEvents();

        // Then
        List<Event> events = result.get(10, TimeUnit.SECONDS);
        assertThat(events).isEmpty();

        verify(1, getRequestedFor(urlEqualTo("/api/events")));
    }

    private ExternalEventApi createExternalEventApi() {
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
