package dev.realtime.tenancy;

import jakarta.validation.constraints.NotBlank;

public record CreateChannelRequest(@NotBlank String name) {
}

