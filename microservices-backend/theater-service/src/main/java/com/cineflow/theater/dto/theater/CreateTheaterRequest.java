package com.cineflow.theater.dto.theater;

import jakarta.validation.constraints.NotBlank;

public record CreateTheaterRequest(
        @NotBlank(message = "Name is required")
        String name,

        @NotBlank(message = "Address is Required")
        String address,

        @NotBlank(message = "City is Required")
        String city
){}