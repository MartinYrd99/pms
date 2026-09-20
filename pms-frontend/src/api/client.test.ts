import { afterEach, describe, expect, it, vi } from "vitest";
import { isApiError } from "./apiError";
import { restoreSession } from "./client";
import { startParkingSession } from "./parkingSessions";
import { setSessionExpiredHandler } from "./sessionExpiredHandler";
import { getAccessToken, setAccessToken } from "./tokenStorage";
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

afterEach(() => {
  setSessionExpiredHandler(null);
  vi.unstubAllGlobals();
});

describe("401 -> refresh -> retry interceptor", () => {
  it("refreshes exactly once on a 401 and retries the request once with the new access token", async () => {
    setAccessToken("old-access");

    const vehicles: VehicleResponse[] = [{ id: 1, plate: "AA000AA", brand: "Toyota", model: "Yaris" }];

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        expect(init?.body).toBeUndefined();
        return jsonResponse({ accessToken: "new-access" });
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
    expect(getAccessToken()).toBe("new-access");
  });

  it("shares one refresh call across two concurrent 401s and retries both requests", async () => {
    setAccessToken("old-access");

    const zones: ZoneResponse[] = [
      { id: 1, name: "Center", city: "Berlin", hourlyRate: 2.5, currency: "EUR", ruleType: "HOURLY" },
    ];

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ accessToken: "new-access" });
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

  it("clears the in-memory access token and fires the session-expired callback exactly once for N concurrent 401s meeting a dead refresh", async () => {
    setAccessToken("old-access");
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

    const results = await Promise.allSettled([listVehicles(), listVehicles(), listVehicles()]);

    expect(results.every((result) => result.status === "rejected")).toBe(true);
    expect(getAccessToken()).toBeNull();

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"));
    expect(refreshCalls).toHaveLength(1);
    expect(sessionExpired).toHaveBeenCalledTimes(1);
  });

  it("fires the session-expired callback exactly once when N concurrent retries all still 401 after a successful refresh", async () => {
    setAccessToken("old-access");
    const sessionExpired = vi.fn();
    setSessionExpiredHandler(sessionExpired);

    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ accessToken: "new-access" });
      }
      if (url.endsWith("/vehicles")) {
        // Every attempt, old or refreshed token alike, is still rejected -- refresh "succeeded"
        // but the session is dead regardless.
        return jsonResponse({ code: "validation.unauthorized", message: "Unauthorized" }, 401);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const results = await Promise.allSettled([listVehicles(), listVehicles(), listVehicles()]);

    expect(results.every((result) => result.status === "rejected")).toBe(true);
    expect(getAccessToken()).toBeNull();

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"));
    expect(refreshCalls).toHaveLength(1);
    expect(sessionExpired).toHaveBeenCalledTimes(1);
  });
});

describe("typed errors", () => {
  it("surfaces a 409 from starting a parking session as a typed ApiError with the blocking session id", async () => {
    setAccessToken("access-token");

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

describe("restoreSession", () => {
  it("populates the access token from a single refresh call", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        expect(init?.body).toBeUndefined();
        return jsonResponse({ accessToken: "restored-access" });
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const restored = await restoreSession();

    expect(restored).toBe(true);
    expect(getAccessToken()).toBe("restored-access");

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"));
    expect(refreshCalls).toHaveLength(1);
  });

  it("resolves as not signed in on a 401 without firing the session-expired callback", async () => {
    const sessionExpired = vi.fn();
    setSessionExpiredHandler(sessionExpired);

    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ code: "auth.invalid_refresh_token", message: "Unauthorized" }, 401);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const restored = await restoreSession();

    expect(restored).toBe(false);
    expect(getAccessToken()).toBeNull();
    expect(sessionExpired).not.toHaveBeenCalled();
  });

  it("shares one refresh call across two concurrent boot calls, as StrictMode's double mount produces", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);

      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ accessToken: "restored-access" });
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const [first, second] = await Promise.all([restoreSession(), restoreSession()]);

    expect(first).toBe(true);
    expect(second).toBe(true);
    expect(getAccessToken()).toBe("restored-access");

    const refreshCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"));
    expect(refreshCalls).toHaveLength(1);
  });
});

describe("credentials", () => {
  it("sends credentials: same-origin on every non-auth request", async () => {
    const fetchMock = vi.fn(async (): Promise<Response> => jsonResponse([]));
    vi.stubGlobal("fetch", fetchMock);

    setAccessToken("access-token");
    await listVehicles();

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/vehicles"),
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });
});
