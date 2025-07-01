package com.fever.marketplace.infrastructure.adapter.provider.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BasePlanXml(
        @JacksonXmlProperty(isAttribute = true, localName = "base_plan_id")
        String basePlanId,

        @JacksonXmlProperty(isAttribute = true, localName = "sell_mode")
        String sellMode,

        @JacksonXmlProperty(isAttribute = true, localName = "title")
        String title,

        @JacksonXmlProperty(isAttribute = true, localName = "organizer_company_id")
        String organizerCompanyId,

        @JacksonXmlProperty(localName = "plan")
        @JacksonXmlElementWrapper(useWrapping = false)
        List<PlanXml> plans
) {}
