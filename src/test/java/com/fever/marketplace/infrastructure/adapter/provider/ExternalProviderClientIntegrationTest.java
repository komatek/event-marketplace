package com.fever.marketplace.infrastructure.adapter.provider;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.adapter.mapper.EventMapper;
import com.fever.marketplace.infrastructure.adapter.provider.xml.BasePlanXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.PlanListXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.PlanXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.ZoneXml;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootTest(classes = {
        ExternalProviderClientIntegrationTest.TestConfig.class,
        ExternalProviderClient.class
})
@EnableAutoConfiguration
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.external-provider.sliding-window-size=5",
        "resilience4j.circuitbreaker.instances.external-provider.failure-rate-threshold=60",
        "resilience4j.circuitbreaker.instances.external-provider.wait-duration-in-open-state=2s",
        "resilience4j.circuitbreaker.instances.external-provider.minimum-number-of-calls=3",
        "resilience4j.retry.instances.external-provider.max-attempts=3",
        "resilience4j.retry.instances.external-provider.wait-duration=100ms",
        "resilience4j.timelimiter.instances.external-provider.timeout-duration=3s"
})
class ExternalProviderClientIntegrationTest {

    @Autowired
    private ExternalProviderClient externalProviderClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @MockBean
    private EventMapper eventMapper;

    private WireMockServer wireMockServer;

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public ExternalEventApi externalEventApi() {
            XmlMapper xmlMapper = new XmlMapper();
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl("http://localhost:8089")
                    .addConverterFactory(JacksonConverterFactory.create(xmlMapper))
                    .build();

            return retrofit.create(ExternalEventApi.class);
        }
    }

    @BeforeEach
    void setUp() {
        // Start WireMock server
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8089));
        wireMockServer.start();
        WireMock.configureFor("localhost", 8089);

        // Reset circuit breaker and retry state
        circuitBreakerRegistry.circuitBreaker("external-provider").reset();
        retryRegistry.retry("external-provider").getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt();

        // Setup default mapper behavior
        Event mockEvent = new Event(
                UUID.randomUUID(),
                "Test Event",
                LocalDate.of(2024, 1, 1),
                LocalTime.of(10, 0),
                LocalDate.of(2024, 1, 1),
                LocalTime.of(12, 0),
                BigDecimal.valueOf(25.50),
                BigDecimal.valueOf(50.00)
        );
        when(eventMapper.mapToOnlineEvents(any())).thenReturn(List.of(mockEvent));
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldRetryOnFailureAndEventuallySucceed() throws Exception {
        // Given
        String xmlResponse = createValidXmlResponse();

        // First two calls fail, third succeeds
        stubFor(get(urlEqualTo("/api/events"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("FirstFailed"));

        stubFor(get(urlEqualTo("/api/events"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("FirstFailed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("SecondFailed"));

        stubFor(get(urlEqualTo("/api/events"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("SecondFailed")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(xmlResponse)));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(10, TimeUnit.SECONDS);

        // Then
        assertThat(events).hasSize(1);
        assertThat(events.get(0).title()).isEqualTo("Test Event");

        // Verify retry attempts
        var retryMetrics = retryRegistry.retry("external-provider").getMetrics();
        assertThat(retryMetrics.getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);

        // Verify all 3 requests were made (2 retries + 1 success)
        verify(3, getRequestedFor(urlEqualTo("/api/events")));
    }

    @Test
    void shouldTriggerCircuitBreakerAfterMultipleFailures() throws Exception {
        // Given - All calls will fail
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse().withStatus(500)));

        // When - Make multiple calls to trigger circuit breaker
        for (int i = 0; i < 5; i++) {
            try {
                externalProviderClient.fetchOnlineEvents().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Expected failures
            }
        }

        // Wait for circuit breaker to open
        await().atMost(3, TimeUnit.SECONDS).until(() ->
                circuitBreakerRegistry.circuitBreaker("external-provider")
                        .getState().name().equals("OPEN"));

        // Then - Circuit breaker should be open
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("external-provider");
        assertThat(circuitBreaker.getState().name()).isEqualTo("OPEN");

        // Now calls should fail fast with fallback
        CompletableFuture<List<Event>> fastFailResult = externalProviderClient.fetchOnlineEvents();
        List<Event> events = fastFailResult.get(5, TimeUnit.SECONDS);

        // Should return empty list from fallback
        assertThat(events).isEmpty();
    }

    @Test
    void shouldUseFallbackWhenCircuitBreakerIsOpen() throws Exception {
        // Given - Force circuit breaker to open
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse().withStatus(500)));

        // Trigger failures to open circuit breaker
        for (int i = 0; i < 5; i++) {
            try {
                externalProviderClient.fetchOnlineEvents().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Expected
            }
        }

        // Wait for circuit breaker to open
        await().atMost(3, TimeUnit.SECONDS).until(() ->
                circuitBreakerRegistry.circuitBreaker("external-provider")
                        .getState().name().equals("OPEN"));

        // When - Make a call while circuit breaker is open
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then - Should get fallback response (empty list)
        assertThat(events).isEmpty();
    }

    @Test
    void shouldRecoverAfterCircuitBreakerHalfOpen() throws Exception {
        // Given - Initially fail to open circuit breaker
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse().withStatus(500)));

        // Trigger circuit breaker open
        for (int i = 0; i < 5; i++) {
            try {
                externalProviderClient.fetchOnlineEvents().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Expected
            }
        }

        // Wait for circuit breaker to open
        await().atMost(3, TimeUnit.SECONDS).until(() ->
                circuitBreakerRegistry.circuitBreaker("external-provider")
                        .getState().name().equals("OPEN"));

        // Wait for circuit breaker to transition to half-open
        await().atMost(5, TimeUnit.SECONDS).until(() ->
                circuitBreakerRegistry.circuitBreaker("external-provider")
                        .getState().name().equals("HALF_OPEN"));

        // Now make the service healthy again
        wireMockServer.resetAll();
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(createValidXmlResponse())));

        // When - Make a successful call
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then - Should succeed and circuit breaker should close
        assertThat(events).hasSize(1);

        await().atMost(3, TimeUnit.SECONDS).until(() ->
                circuitBreakerRegistry.circuitBreaker("external-provider")
                        .getState().name().equals("CLOSED"));
    }

    @Test
    void shouldHandleTimeoutCorrectly() throws Exception {
        // Given - API responds with long delay (longer than timeout)
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(createValidXmlResponse())
                        .withFixedDelay(5000))); // 5 seconds delay, timeout is 3 seconds

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(10, TimeUnit.SECONDS);

        // Then - Should timeout and fallback to empty list
        assertThat(events).isEmpty();
    }

    @Test
    void shouldSucceedOnFirstAttemptWhenServiceIsHealthy() throws Exception {
        // Given
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(createValidXmlResponse())));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).hasSize(1);
        assertThat(events.get(0).title()).isEqualTo("Test Event");

        // Verify only one call was made (no retries)
        verify(1, getRequestedFor(urlEqualTo("/api/events")));

        // Verify no retries occurred
        var retryMetrics = retryRegistry.retry("external-provider").getMetrics();
        assertThat(retryMetrics.getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(0);
        assertThat(retryMetrics.getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(0);
    }

    @Test
    void shouldHandleNetworkException() throws Exception {
        // Given - Network failure
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(10, TimeUnit.SECONDS);

        // Then - Should fallback to empty list after retries
        assertThat(events).isEmpty();

        // Verify retries were attempted
        var retryMetrics = retryRegistry.retry("external-provider").getMetrics();
        assertThat(retryMetrics.getNumberOfFailedCallsWithRetryAttempt()).isGreaterThan(0);
    }

    @Test
    void shouldHandleMalformedXmlResponse() throws Exception {
        // Given
        String malformedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <planList version="1.0">
                <o>
                    <base_plan base_plan_id="1" sell_mode="online" title="Test Event"
            """; // Intentionally malformed

        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(malformedXml)));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(10, TimeUnit.SECONDS);

        // Then - Should fallback to empty list after retries
        assertThat(events).isEmpty();

        // Verify retries were attempted due to parsing error
        var retryMetrics = retryRegistry.retry("external-provider").getMetrics();
        assertThat(retryMetrics.getNumberOfFailedCallsWithRetryAttempt()).isGreaterThan(0);
    }

    @Test
    void shouldTrackResilienceMetrics() throws Exception {
        // Given
        stubFor(get(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(createValidXmlResponse())));

        // When - Make successful call
        externalProviderClient.fetchOnlineEvents().get(5, TimeUnit.SECONDS);

        // Then - Verify metrics
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("external-provider");
        var retryMetrics = retryRegistry.retry("external-provider").getMetrics();

        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getState().name()).isEqualTo("CLOSED");
        assertThat(retryMetrics.getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    private String createValidXmlResponse() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <planList version="1.0">
                <o>
                    <base_plan base_plan_id="1" sell_mode="online" title="Test Event" organizer_company_id="company1">
                        <plan plan_id="plan1" plan_start_date="2024-01-01T10:00:00" plan_end_date="2024-01-01T12:00:00" 
                              sell_from="2023-12-01T00:00:00" sell_to="2024-01-01T09:00:00" sold_out="false">
                            <zone zone_id="zone1" capacity="100" price="25.50" name="General Admission" numbered="false"/>
                        </plan>
                    </base_plan>
                </o>
            </planList>
            """;
    }
}
