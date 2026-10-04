package com.cineflow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OtpSendRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotNull OtpType type
) {
}
