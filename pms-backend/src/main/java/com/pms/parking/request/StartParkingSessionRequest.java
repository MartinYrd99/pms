package com.pms.parking.request;

import jakarta.validation.constraints.NotNull;

public record StartParkingSessionRequest(@NotNull Long vehicleId, @NotNull Long zoneId) {
}