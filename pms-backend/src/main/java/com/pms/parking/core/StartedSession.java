package com.pms.parking.core;

import com.pms.vehicle.core.Vehicle;
import com.pms.zone.core.Zone;

/**
 * The outcome of starting a session, bundled with the vehicle and zone already loaded to build it
 * — avoids the controller re-querying rows the service just fetched.
 */
public record StartedSession(ParkingSession session, Vehicle vehicle, Zone zone) {
}