package com.cineflow.booking.dto;

import java.time.OffsetDateTime;

public record HoldResponse(OffsetDateTime holdExpiresAt) {
}
