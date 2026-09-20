import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { isApiError } from "./apiError";
import { startParkingSession } from "./parkingSessions";
import { setSessionExpiredHandler } from "./sessionExpiredHandler";
import { getTokens, setTokens } from "./tokenStorage";
import type { SessionConflictResponse } from "./types/parkingSession";
import type { VehicleResponse } from "./types/vehicle";
import type { ZoneResponse } from "./types/zone";
import { listVehicles } from "./vehicles";
import { listZones } from "./zones";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function authHeaderOf(init?: RequestInit): string | null {
  return new Headers(init?.headers).get("Authorization");
}

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  setSessionExpiredHandler(null);
  vi.unstubAllGlobals();
});

describe("401 -> refresh -> retry interceptor", () => {
  it("refreshes exactly once on a 401 and retries the request once with the new access token", async () => {
    setTokens({ accessToken: "old-access", refreshToken: "old-refresh" });

    const vehicles: VehicleResponse[] = [{ id: 1, plate: "AA000AA", brand: "Toyota", model: "Yaris" }];

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ accessToken: "new-access", refreshToken: "new-refresh" });
      }
      if (url.endsWith("/vehicles")) {
        if (authHeaderOf(init) === "Bearer old-access") {
          return jsonResponse({ code: "validation.unauthorized", message: "Unauthorized" }, 401);
        }
        if (authHeaderOf(init) === "Bearer new-access") {
          return jsonResponse(vehicles);
        }
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await listVehicles();

    expect(result).toEqual(vehicles);

    const vehicleCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/vehicles"));
    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"));
    expect(vehicleCalls).toHaveLength(2);
    expect(refreshCalls).toHaveLength(1);
    expect(authHeaderOf(vehicleCalls[1][1])).toBe("Bearer new-access");
    expect(getTokens()).toEqual({ accessToken: "new-access", refreshToken: "new-refresh" });
  });

  it("shares one refresh call across two concurrent 401s and retries both requests", async () => {
    setTokens({ accessToken: "old-access", refreshToken: "old-refresh" });

    const zones: ZoneResponse[] = [
      { id: 1, name: "Center", city: "Berlin", hourlyRate: 2.5, currency: "EUR", ruleType: "HOURLY" },
    ];

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ accessToken: "new-access", refreshToken: "new-refresh" });
      }
      if (url.endsWith("/zones")) {
        if (authHeaderOf(init) === "Bearer old-access") {
          return jsonResponse({ code: "validation.unauthorized", message: "Unauthorized" }, 401);
        }
        return jsonResponse(zones);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const [first, second] = await Promise.all([listZones(), listZones()]);

    expect(first).toEqual(zones);
    expect(second).toEqual(zones);

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"));
    expect(refreshCalls).toHaveLength(1);
  });

  it("clears stored tokens and fires the session-expired callback once when refresh fails", async () => {
    setTokens({ accessToken: "old-access", refreshToken: "old-refresh" });
    const sessionExpired = vi.fn();
    setSessionExpiredHandler(sessionExpired);

    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ code: "validation.unauthorized", message: "Refresh token invalid" }, 401);
      }
      if (url.endsWith("/vehicles")) {
        return jsonResponse({ code: "validation.unauthorized", message: "Unauthorized" }, 401);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(listVehicles()).rejects.toThrow();

    expect(getTokens()).toBeNull();
    expect(sessionExpired).toHaveBeenCalledTimes(1);
  });
});

describe("typed errors", () => {
  it("surfaces a 409 from starting a parking session as a typed ApiError with the blocking session id", async () => {
    setTokens({ accessToken: "access-token", refreshToken: "refresh-token" });

    const conflictBody: SessionConflictResponse = {
      code: "validation.conflict",
      message: "Vehicle already has an active session",
      blockingSessionId: 42,
    };

    const fetchMock = vi.fn(async (): Promise<Response> => jsonResponse(conflictBody, 409));
    vi.stubGlobal("fetch", fetchMock);

    const error: unknown = await startParkingSession({ vehicleId: 1, zoneId: 2 }).catch(
      (caught: unknown) => caught,
    );

    if (!isApiError<SessionConflictResponse>(error)) {
      throw new Error("Expected an ApiError");
    }
    expect(error.status).toBe(409);
    expect(error.code).toBe("validation.conflict");
    expect(error.message).toBe("Vehicle already has an active session");
    expect(error.body.blockingSessionId).toBe(42);
  });
});
