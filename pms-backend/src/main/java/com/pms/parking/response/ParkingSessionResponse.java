package com.pms.parking.response;

import com.pms.payment.core.PaymentStatus;
import com.pms.parking.core.StartedSession;
import java.math.BigDecimal;
import java.time.Instant;

public record ParkingSessionResponse(
        Long id,
        ParkingSessionVehicle vehicle,
        ParkingSessionZone zone,
        Instant startedAt,
        Instant endedAt,
        BigDecimal amount,
        PaymentStatus paymentStatus) {

    public static ParkingSessionResponse from(StartedSession started) {
        return new ParkingSessionResponse(
                started.session().getId(),
                new ParkingSessionVehicle(
                        started.vehicle().getId(),
                        started.vehicle().getPlate(),
                        started.vehicle().getBrand(),
                        started.vehicle().getModel()),
                new ParkingSessionZone(started.zone().getId(), started.zone().getName(), started.zone().getCity()),
                started.session().getStartedAt(),
                started.session().getEndedAt(),
                started.session().getAmount(),
                started.paymentStatus());
    }
}