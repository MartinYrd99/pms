export { ApiError, isApiError } from "./apiError";
export { setSessionExpiredHandler } from "./sessionExpiredHandler";
export { restoreSession } from "./client";

export { login, logout, refresh, register } from "./auth";
export { createVehicle, listVehicles } from "./vehicles";
export { listZones } from "./zones";
export {
  endParkingSession,
  getParkingSession,
  listActiveParkingSessions,
  listParkingHistory,
  startParkingSession,
} from "./parkingSessions";
export { getParkingSessionPayment, payForParkingSession } from "./payments";

export type { ErrorResponseBody, PageResponse } from "./types/common";
export type { AccessTokenResponse, LoginRequest, RegisterRequest, RegisterResponse } from "./types/auth";
export type { VehicleRequest, VehicleResponse } from "./types/vehicle";
export type { RuleType, ZoneResponse } from "./types/zone";
export type {
  ParkingHistoryQuery,
  ParkingSessionResponse,
  ParkingSessionVehicle,
  ParkingSessionZone,
  PaymentStatus,
  SessionAlreadyEndedResponse,
  SessionConflictResponse,
  StartParkingSessionRequest,
} from "./types/parkingSession";
export type { PaymentResponse } from "./types/payment";
