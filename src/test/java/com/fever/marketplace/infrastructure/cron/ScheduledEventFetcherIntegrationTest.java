package com.fever.marketplace.infrastructure.cron;

import com.fever.marketplace.application.SyncEvents;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Simple integration test for ScheduledEventFetcher
 * Verifies that the use case is called by the scheduler
 */
@SpringBootTest(classes = {
        ScheduledEventFetcher.class,
        ScheduledEventFetcherIntegrationTest.TestSchedulingConfig.class
})
@EnableScheduling
@TestPropertySource(properties = {
        "fever.sync.enabled=true",
        "fever.sync.interval=100" // 100ms for fast testing
})
class ScheduledEventFetcherIntegrationTest {

    @MockBean
    private SyncEvents syncEvents;

    @Configuration
    @EnableScheduling
    static class TestSchedulingConfig {
        // Minimal configuration to enable scheduling
    }

    @Test
    void shouldCallSyncEventsUseCase() {
        // When - Wait for the scheduler to execute
        await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(syncEvents, atLeastOnce()).syncEventsFromProvider());
    }

    @Test
    void shouldCallSyncEventsMultipleTimes() {
        // When - Wait for multiple executions
        await()
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> verify(syncEvents, atLeast(3)).syncEventsFromProvider());
    }

    @Test
    void shouldContinueCallingEvenAfterException() {
        // Given - First call throws exception
        doThrow(new RuntimeException("Sync failed"))
                .doNothing() // Second call succeeds
                .when(syncEvents).syncEventsFromProvider();

        // When - Wait for scheduler to call again after exception
        await()
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> verify(syncEvents, atLeast(2)).syncEventsFromProvider());
    }
}

/**
 * Test that scheduler is disabled when configured
 */
@SpringBootTest(classes = {
        ScheduledEventFetcher.class,
        ScheduledEventFetcherDisabledTest.TestSchedulingConfig.class
})
@EnableScheduling
@TestPropertySource(properties = {
        "fever.sync.enabled=false",
        "fever.sync.interval=100"
})
class ScheduledEventFetcherDisabledTest {

    @MockBean
    private SyncEvents syncEvents;

    @org.springframework.context.annotation.Configuration
    @EnableScheduling
    static class TestSchedulingConfig {
        // Minimal configuration
    }

    @Test
    void shouldNotCallSyncEventsWhenDisabled() throws Exception {
        // When - Wait enough time for potential executions
        Thread.sleep(500);

        // Then - Verify no calls were made
        verify(syncEvents, never()).syncEventsFromProvider();
    }
}
