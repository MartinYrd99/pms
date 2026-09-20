package com.pms.parking.core;

import com.pms.payment.core.PaymentStatus;
import com.pms.vehicle.core.Vehicle;
import com.pms.zone.core.Zone;

/**
 * A parking session bundled with its vehicle, zone and payment status already loaded
 */
public record StartedSession(ParkingSession session, Vehicle vehicle, Zone zone, PaymentStatus paymentStatus) {
}