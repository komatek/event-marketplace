package com.fever.marketplace.infrastructure.adapter.provider;

import com.fever.marketplace.infrastructure.adapter.provider.xml.PlanListXml;
import retrofit2.Call;
import retrofit2.http.GET;

/**
 * Interface representing the external event API.
 * This interface defines the contract for interacting with the external API to fetch event data.
 */
public interface ExternalEventApi {

    /**
     * Fetches a list of events from the external API.
     * The endpoint returns the events in XML format, which is mapped to the `PlanListXml` object.
     *
     * @return A `Call` object encapsulating the HTTP request and response for fetching events.
     */
    @GET("/api/events")
    Call<PlanListXml> fetchEvents();
}
