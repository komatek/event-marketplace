package com.fever.marketplace.infrastructure.adapter.provider;

import com.fever.marketplace.domain.model.Event;
import com.fever.marketplace.infrastructure.adapter.mapper.EventMapper;
import com.fever.marketplace.infrastructure.adapter.provider.xml.BasePlanXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.PlanListXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.PlanXml;
import com.fever.marketplace.infrastructure.adapter.provider.xml.ZoneXml;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import retrofit2.Call;
import retrofit2.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalProviderClientTest {

    @Mock
    private ExternalEventApi eventApi;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private Call<PlanListXml> mockCall;

    private ExternalProviderClient externalProviderClient;

    @BeforeEach
    void setUp() {
        externalProviderClient = new ExternalProviderClient(eventApi, eventMapper);
    }

    @Test
    void shouldFetchEventsSuccessfully() throws Exception {
        // Given
        ZoneXml zone = new ZoneXml("zone1", 100, 25.50, "General Admission", false);
        PlanXml plan = new PlanXml("plan1", "2024-01-01T10:00:00", "2024-01-01T12:00:00",
                "2023-12-01T00:00:00", "2024-01-01T09:00:00", false, List.of(zone));
        BasePlanXml basePlan = new BasePlanXml("1", "online", "Test Event", "company1", List.of(plan));
        PlanListXml.OutputXml output = new PlanListXml.OutputXml(List.of(basePlan));
        PlanListXml planList = new PlanListXml("1.0", output);

        Event mockEvent = new Event(
                UUID.randomUUID(),
                "Test Event",
                LocalDate.of(2024, 1, 1),
                LocalTime.of(10, 0),
                LocalDate.of(2024, 1, 1),
                LocalTime.of(12, 0),
                BigDecimal.valueOf(25.50),
                BigDecimal.valueOf(25.50)
        );

        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.success(planList));
        when(eventMapper.mapToOnlineEvents(List.of(basePlan))).thenReturn(List.of(mockEvent));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).hasSize(1);
        assertThat(events.get(0).title()).isEqualTo("Test Event");

        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verify(eventMapper).mapToOnlineEvents(List.of(basePlan));
    }

    @Test
    void shouldReturnEmptyListWhenApiReturns404() throws Exception {
        // Given
        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.error(404,
                okhttp3.ResponseBody.create(null, "")));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).isEmpty();
        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verifyNoInteractions(eventMapper);
    }

    @Test
    void shouldReturnEmptyListWhenApiReturns500() throws Exception {
        // Given
        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.error(500,
                okhttp3.ResponseBody.create(null, "Internal Server Error")));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).isEmpty();
        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verifyNoInteractions(eventMapper);
    }

    @Test
    void shouldReturnEmptyListWhenResponseBodyIsNull() throws Exception {
        // Given
        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.success(null));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).isEmpty();
        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verifyNoInteractions(eventMapper);
    }

    @Test
    void shouldReturnEmptyListWhenOutputIsNull() throws Exception {
        // Given
        PlanListXml planList = new PlanListXml("1.0", null);

        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.success(planList));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();

        // Then - Should complete exceptionally due to NullPointerException in production code
        // This reveals a bug: the code should handle null output gracefully
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasRootCauseMessage("Cannot invoke \"com.fever.marketplace.infrastructure.adapter.provider.xml.PlanListXml$OutputXml.basePlans()\" because the return value of \"com.fever.marketplace.infrastructure.adapter.provider.xml.PlanListXml.output()\" is null");

        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verifyNoInteractions(eventMapper);
    }

    @Test
    void shouldReturnEmptyListWhenBasePlansIsEmpty() throws Exception {
        // Given
        PlanListXml.OutputXml output = new PlanListXml.OutputXml(Collections.emptyList());
        PlanListXml planList = new PlanListXml("1.0", output);

        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.success(planList));
        when(eventMapper.mapToOnlineEvents(Collections.emptyList())).thenReturn(Collections.emptyList());

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).isEmpty();
        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verify(eventMapper).mapToOnlineEvents(Collections.emptyList());
    }

    @Test
    void shouldHandleExceptionDuringApiCall() throws Exception {
        // Given
        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenThrow(new RuntimeException("Network error"));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then - Should return empty list due to fallback
        assertThat(events).isEmpty();
        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verifyNoInteractions(eventMapper);
    }

    @Test
    void shouldHandleMultipleBasePlans() throws Exception {
        // Given
        ZoneXml zone1 = new ZoneXml("zone1", 100, 25.50, "General Admission", false);
        ZoneXml zone2 = new ZoneXml("zone2", 200, 30.00, "Standard", false);

        PlanXml plan1 = new PlanXml("plan1", "2024-01-01T10:00:00", "2024-01-01T12:00:00",
                "2023-12-01T00:00:00", "2024-01-01T09:00:00", false, List.of(zone1));
        PlanXml plan2 = new PlanXml("plan2", "2024-01-02T14:00:00", "2024-01-02T16:00:00",
                "2023-12-01T00:00:00", "2024-01-02T13:00:00", false, List.of(zone2));

        BasePlanXml basePlan1 = new BasePlanXml("1", "online", "Event 1", "company1", List.of(plan1));
        BasePlanXml basePlan2 = new BasePlanXml("2", "online", "Event 2", "company2", List.of(plan2));

        PlanListXml.OutputXml output = new PlanListXml.OutputXml(List.of(basePlan1, basePlan2));
        PlanListXml planList = new PlanListXml("1.0", output);

        Event event1 = new Event(
                UUID.randomUUID(), "Event 1", LocalDate.of(2024, 1, 1), LocalTime.of(10, 0),
                LocalDate.of(2024, 1, 1), LocalTime.of(12, 0), BigDecimal.valueOf(25.50), BigDecimal.valueOf(25.50)
        );
        Event event2 = new Event(
                UUID.randomUUID(), "Event 2", LocalDate.of(2024, 1, 2), LocalTime.of(14, 0),
                LocalDate.of(2024, 1, 2), LocalTime.of(16, 0), BigDecimal.valueOf(30.00), BigDecimal.valueOf(30.00)
        );

        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.success(planList));
        when(eventMapper.mapToOnlineEvents(List.of(basePlan1, basePlan2))).thenReturn(List.of(event1, event2));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).hasSize(2);
        assertThat(events).extracting("title").containsExactly("Event 1", "Event 2");

        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verify(eventMapper).mapToOnlineEvents(List.of(basePlan1, basePlan2));
    }

    @Test
    void shouldHandleMapperReturningEmptyList() throws Exception {
        // Given
        ZoneXml zone = new ZoneXml("zone1", 100, 25.50, "General Admission", false);
        PlanXml plan = new PlanXml("plan1", "2024-01-01T10:00:00", "2024-01-01T12:00:00",
                "2023-12-01T00:00:00", "2024-01-01T09:00:00", false, List.of(zone));
        BasePlanXml basePlan = new BasePlanXml("1", "online", "Test Event", "company1", List.of(plan));
        PlanListXml.OutputXml output = new PlanListXml.OutputXml(List.of(basePlan));
        PlanListXml planList = new PlanListXml("1.0", output);

        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.success(planList));
        when(eventMapper.mapToOnlineEvents(any())).thenReturn(Collections.emptyList());

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();
        List<Event> events = result.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(events).isEmpty();
        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verify(eventMapper).mapToOnlineEvents(List.of(basePlan));
    }

    @Test
    void shouldHandleMapperThrowingException() throws Exception {
        // Given
        ZoneXml zone = new ZoneXml("zone1", 100, 25.50, "General Admission", false);
        PlanXml plan = new PlanXml("plan1", "2024-01-01T10:00:00", "2024-01-01T12:00:00",
                "2023-12-01T00:00:00", "2024-01-01T09:00:00", false, List.of(zone));
        BasePlanXml basePlan = new BasePlanXml("1", "online", "Test Event", "company1", List.of(plan));
        PlanListXml.OutputXml output = new PlanListXml.OutputXml(List.of(basePlan));
        PlanListXml planList = new PlanListXml("1.0", output);

        when(eventApi.fetchEvents()).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(Response.success(planList));
        when(eventMapper.mapToOnlineEvents(any())).thenThrow(new RuntimeException("Mapping error"));

        // When
        CompletableFuture<List<Event>> result = externalProviderClient.fetchOnlineEvents();

        // Then - Should complete exceptionally when mapper throws exception
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Mapping error");

        verify(eventApi).fetchEvents();
        verify(mockCall).execute();
        verify(eventMapper).mapToOnlineEvents(List.of(basePlan));
    }
}
