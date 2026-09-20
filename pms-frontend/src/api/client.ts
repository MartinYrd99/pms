import { ApiError } from "./apiError";
import { notifySessionExpired } from "./sessionExpiredHandler";
import { clearAccessToken, getAccessToken, setAccessToken } from "./tokenStorage";
import type { ErrorResponseBody } from "./types/common";
import type { AccessTokenResponse } from "./types/auth";

const API_BASE_PATH = "/api/v1";

export type HttpMethod = "GET" | "POST";

export interface ApiRequestOptions<TBody> {
  method: HttpMethod;
  path: string;
  body?: TBody;
  query?: Record<string, string | number | undefined>;
  /** False for the three public endpoints (register, login, refresh), which never carry a token. */
  auth?: boolean;
}

/** The single entry point every typed endpoint function funnels through. */
export async function apiRequest<TResponse, TBody = undefined>(
  options: ApiRequestOptions<TBody>,
): Promise<TResponse> {
  return executeRequest<TResponse, TBody>(options, false);
}

async function executeRequest<TResponse, TBody>(
  options: ApiRequestOptions<TBody>,
  isRetry: boolean,
  episode?: RefreshEpisode,
): Promise<TResponse> {
  const response = await sendRequest(options);

  if (response.ok) {
    return readSuccessBody<TResponse>(response);
  }

  const requiresAuth = options.auth !== false;
  const isUnauthorized = response.status === 401;

  if (isUnauthorized && requiresAuth && !isRetry) {
    const refresh = refreshTokens();
    const refreshed = await refresh.result;
    if (refreshed) {
      return executeRequest<TResponse, TBody>(options, true, refresh.episode);
    }

    // The shared refresh already notified the session-expired handler at most once for this episode.
    throw await buildApiError(response);
  }

  if (isUnauthorized && requiresAuth && isRetry) {
    clearAccessToken();
    // Same episode as the refresh that produced this retry's token, so a burst of concurrent
    // retries that all still 401 still only notifies once.
    episode?.reportSessionDead();
  }

  throw await buildApiError(response);
}

async function sendRequest<TBody>(options: ApiRequestOptions<TBody>): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json" };

  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  if (options.auth !== false) {
    const accessToken = getAccessToken();

    if (accessToken !== null) {
      headers.Authorization = `Bearer ${accessToken}`;
    }
  }

  return fetch(buildUrl(options.path, options.query), {
    method: options.method,
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    credentials: "same-origin",
  });
}

function buildUrl(path: string, query?: Record<string, string | number | undefined>): string {
  const url = `${API_BASE_PATH}${path}`;
  if (!query) {
    return url;
  }

  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.set(key, String(value));
    }
  }

  const queryString = params.toString();

  return queryString ? `${url}?${queryString}` : url;
}

async function readSuccessBody<TResponse>(response: Response): Promise<TResponse> {
  if (response.status === 204) {
    return undefined as TResponse;
  }

  return (await response.json()) as TResponse;
}

async function buildApiError(response: Response): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null);
  const { code, message } = extractErrorFields(body, response.status);

  return new ApiError(response.status, code, message, body);
}

function extractErrorFields(body: unknown, status: number): ErrorResponseBody {
  if (isErrorResponseBody(body)) {
    return { code: body.code, message: body.message };
  }

  return { code: "error.unknown", message: `Request failed with status ${status}` };
}

function isErrorResponseBody(value: unknown): value is ErrorResponseBody {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const record = value as Record<string, unknown>;

  return typeof record.code === "string" && typeof record.message === "string";
}

// One refresh can be awaited by many callers (concurrent 401s, or the 401 interceptor racing
// app-boot restoreSession()), but the "session expired" callback must fire at most once for it.
// The latch lives on the episode object itself -- shared by reference with every waiter of that
// one refresh -- so exactly-once holds structurally, not because every caller behaves.
class RefreshEpisode {
  private notifyIntent = false;
  private notified = false;

  /** Called by a waiter that wants a failed refresh treated as "session expired", not "not signed in". */
  wantNotifyOnFailure(): void {
    this.notifyIntent = true;
  }

  /** Fires the session-expired callback once for this episode, if any waiter asked for it. */
  reportSessionDead(): void {
    if (this.notified || !this.notifyIntent) {
      return;
    }

    this.notified = true;
    notifySessionExpired();
  }
}

interface SharedRefresh {
  episode: RefreshEpisode;
  result: Promise<boolean>;
}

// The refresh cookie rotates, so every caller that needs a refresh -- the 401 interceptor and the
// app-boot restoreSession() alike -- shares this single in-flight call. React StrictMode's double
// mount otherwise fires two boot refreshes that would each burn a single-use refresh token.
let inFlight: SharedRefresh | null = null;

function sharedRefresh(): SharedRefresh {
  if (inFlight !== null) {
    return inFlight;
  }

  const episode = new RefreshEpisode();
  const result = performRefresh()
    .then((succeeded) => {
      if (!succeeded) {
        episode.reportSessionDead();
      }

      return succeeded;
    })
    .finally(() => {
      inFlight = null;
    });

  inFlight = { episode, result };

  return inFlight;
}

async function performRefresh(): Promise<boolean> {
  const accessToken = await requestRefreshedAccessToken();

  if (accessToken === null) {
    clearAccessToken();

    return false;
  }

  setAccessToken(accessToken);

  return true;
}

// Marks the current (or about-to-start) shared refresh as one whose failure should be treated as
// "session expired" -- see RefreshEpisode above for how that stays exactly-once regardless of how
// many 401'd requests join this same in-flight refresh.
function refreshTokens(): SharedRefresh {
  const refresh = sharedRefresh();
  refresh.episode.wantNotifyOnFailure();

  return refresh;
}

/**
 * Restores a session once at app boot from the HttpOnly refresh cookie alone. A missing or
 * expired cookie just means "nobody is signed in yet" here, not a session dying mid-visit, so
 * unlike the interceptor's own refresh this never fires the session-expired callback.
 */
export function restoreSession(): Promise<boolean> {
  return sharedRefresh().result;
}

/** POSTs /auth/refresh with no body; the browser attaches the HttpOnly cookie on its own. */
async function requestRefreshedAccessToken(): Promise<string | null> {
  try {
    const response = await fetch(buildUrl("/auth/refresh"), {
      method: "POST",
      headers: { Accept: "application/json" },
      credentials: "same-origin",
    });

    if (!response.ok) {
      return null;
    }

    const tokens = (await response.json()) as AccessTokenResponse;

    return tokens.accessToken;
  } catch {
    return null;
  }
}
