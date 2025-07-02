package com.fever.marketplace.infrastructure.adapter.provider.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;

public record PlanXml(
        @JacksonXmlProperty(isAttribute = true, localName = "plan_id")
        String planId,

        @JacksonXmlProperty(isAttribute = true, localName = "plan_start_date")
        String startDate,

        @JacksonXmlProperty(isAttribute = true, localName = "plan_end_date")
        String endDate,

        @JacksonXmlProperty(isAttribute = true, localName = "sell_from")
        String sellFrom,

        @JacksonXmlProperty(isAttribute = true, localName = "sell_to")
        String sellTo,

        @JacksonXmlProperty(isAttribute = true, localName = "sold_out")
        boolean soldOut,

        @JacksonXmlProperty(localName = "zone")
        @JacksonXmlElementWrapper(useWrapping = false)
        List<ZoneXml> zones
) {}
