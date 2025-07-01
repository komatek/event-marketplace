package com.fever.marketplace.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record Event(
        UUID id,
        String title,
        LocalDate startDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        BigDecimal minPrice,
        BigDecimal maxPrice
) {}
