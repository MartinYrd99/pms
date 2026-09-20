export type PaymentStatus = "PENDING" | "COMPLETED" | "FAILED";

export interface ParkingSessionVehicle {
  id: number;
  plate: string;
  brand: string;
  model: string;
}

export interface ParkingSessionZone {
  id: number;
  name: string;
  city: string;
}

export interface ParkingSessionResponse {
  id: number;
  vehicle: ParkingSessionVehicle;
  zone: ParkingSessionZone;
  startedAt: string;
  endedAt: string | null;
  amount: number | null;
  // Null until a payment has been created for this session.
  paymentStatus: PaymentStatus | null;
}

export interface StartParkingSessionRequest {
  vehicleId: number;
  zoneId: number;
}

export interface ParkingHistoryQuery {
  page?: number;
  size?: number;
}

/**
 * 409 body from starting a session: the vehicle already has this other session blocking it. The id
 * is null when two starts for the same vehicle race each other — the loser trips the database's
 * unique constraint before it ever loads the winning session, so the id is unknown at that point.
 */
export interface SessionConflictResponse {
  code: string;
  message: string;
  blockingSessionId: number | null;
}

/** 409 body from ending a session that was already ended: the session as it now stands. */
export interface SessionAlreadyEndedResponse {
  code: string;
  message: string;
  session: ParkingSessionResponse;
}