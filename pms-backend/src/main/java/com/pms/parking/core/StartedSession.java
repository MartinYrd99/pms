package com.pms.parking.core;

import com.pms.vehicle.core.Vehicle;
import com.pms.zone.core.Zone;

/**
 * A parking session bundled with its vehicle and zone already loaded — avoids the controller
 * re-querying rows the service just fetched. Used for any lifecycle stage (started, active,
 * ended), not only the initial start.
 */
public record StartedSession(ParkingSession session, Vehicle vehicle, Zone zone) {
}