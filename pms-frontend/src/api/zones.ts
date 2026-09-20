import { apiRequest } from "./client";
import type { ZoneResponse } from "./types/zone";

export async function listZones(): Promise<ZoneResponse[]> {
  return apiRequest<ZoneResponse[]>({ method: "GET", path: "/zones" });
}