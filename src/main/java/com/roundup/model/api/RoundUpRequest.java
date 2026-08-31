package com.roundup.model.api;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record RoundUpRequest(
        @NotNull(message = "startDate is required") LocalDate startDate,
        @NotNull(message = "transferUid is required") UUID transferUid) {
}
