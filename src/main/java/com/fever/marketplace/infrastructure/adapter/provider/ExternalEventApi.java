package com.fever.marketplace.infrastructure.adapter.provider;

import com.fever.marketplace.infrastructure.adapter.provider.xml.PlanListXml;
import retrofit2.Call;
import retrofit2.http.GET;

public interface ExternalEventApi {
    @GET("/api/events")
    Call<PlanListXml> fetchEvents();
}
