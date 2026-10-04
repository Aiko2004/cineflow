package com.cineflow.screening.document;

import com.cineflow.contracts.SeatType;

import java.math.BigDecimal;

public record SeatTypePrice(SeatType type, BigDecimal price) {}
