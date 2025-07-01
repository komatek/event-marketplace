package com.fever.marketplace.infrastructure.adapter.provider.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public record ZoneXml(
        @JacksonXmlProperty(isAttribute = true, localName = "zone_id")
        String zoneId,

        @JacksonXmlProperty(isAttribute = true, localName = "capacity")
        int capacity,

        @JacksonXmlProperty(isAttribute = true, localName = "price")
        double price,

        @JacksonXmlProperty(isAttribute = true, localName = "name")
        String name,

        @JacksonXmlProperty(isAttribute = true, localName = "numbered")
        boolean numbered
) {}
