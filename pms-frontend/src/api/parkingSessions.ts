import { apiRequest } from "./client";
import type { PageResponse } from "./types/common";
import type {
  ParkingHistoryQuery,
  ParkingSessionResponse,
  StartParkingSessionRequest,
} from "./types/parkingSession";

export async function startParkingSession(
  request: StartParkingSessionRequest,
): Promise<ParkingSessionResponse> {
  return apiRequest<ParkingSessionResponse, StartParkingSessionRequest>({
    method: "POST",
    path: "/parking-sessions",
    body: request,
  });
}

export async function listActiveParkingSessions(): Promise<ParkingSessionResponse[]> {
  return apiRequest<ParkingSessionResponse[]>({
    method: "GET",
    path: "/parking-sessions/active",
  });
}

export async function endParkingSession(id: number): Promise<ParkingSessionResponse> {
  return apiRequest<ParkingSessionResponse>({
    method: "POST",
    path: `/parking-sessions/${id}/end`,
  });
}

export async function getParkingSession(id: number): Promise<ParkingSessionResponse> {
  return apiRequest<ParkingSessionResponse>({
    method: "GET",
    path: `/parking-sessions/${id}`,
  });
}

export async function listParkingHistory(
  query: ParkingHistoryQuery = {},
): Promise<PageResponse<ParkingSessionResponse>> {
  return apiRequest<PageResponse<ParkingSessionResponse>>({
    method: "GET",
    path: "/parking-sessions",
    query: { page: query.page, size: query.size },
  });
}