package com.fever.marketplace.infrastructure.adapter.provider.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "planList")
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanListXml(
        @JacksonXmlProperty(isAttribute = true)
        String version,

        @JacksonXmlProperty(localName = "output")
        OutputXml output
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutputXml(
            @JacksonXmlProperty(localName = "base_plan")
            @JacksonXmlElementWrapper(useWrapping = false)
            List<BasePlanXml> basePlans
    ) {}
}
