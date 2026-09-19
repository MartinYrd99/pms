package com.pms.parking.response;

import java.time.Instant;

public record ParkingSessionResponse(Long id, ParkingSessionVehicle vehicle, ParkingSessionZone zone, Instant startedAt) {
}