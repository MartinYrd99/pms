import { apiRequest } from "./client";
import type { VehicleRequest, VehicleResponse } from "./types/vehicle";

export async function listVehicles(): Promise<VehicleResponse[]> {
  return apiRequest<VehicleResponse[]>({ method: "GET", path: "/vehicles" });
}

export async function createVehicle(request: VehicleRequest): Promise<VehicleResponse> {
  return apiRequest<VehicleResponse, VehicleRequest>({
    method: "POST",
    path: "/vehicles",
    body: request,
  });
}