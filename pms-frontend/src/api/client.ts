import { ApiError } from "./apiError";
import { notifySessionExpired } from "./sessionExpiredHandler";
import { clearTokens, getAccessToken, getTokens, setTokens } from "./tokenStorage";
import type { ErrorResponseBody } from "./types/common";
import type { LoginResponse, RefreshTokenRequest } from "./types/auth";

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
): Promise<TResponse> {
  const response = await sendRequest(options);

  if (response.ok) {
    return readSuccessBody<TResponse>(response);
  }

  const requiresAuth = options.auth !== false;
  const isUnauthorized = response.status === 401;

  if (isUnauthorized && requiresAuth && !isRetry) {
    const refreshed = await refreshTokens();
    if (refreshed) {
      return executeRequest<TResponse, TBody>(options, true);
    }

    // performRefresh() already cleared tokens and notified the session-expired handler.
    throw await buildApiError(response);
  }

  if (isUnauthorized && requiresAuth && isRetry) {
    clearTokens();
    notifySessionExpired();
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

// Refresh tokens rotate, so every 401 that needs one shares this single in-flight call.
let inFlightRefresh: Promise<boolean> | null = null;

function refreshTokens(): Promise<boolean> {
  if (inFlightRefresh === null) {
    inFlightRefresh = performRefresh().finally(() => {
      inFlightRefresh = null;
    });
  }

  return inFlightRefresh;
}

async function performRefresh(): Promise<boolean> {
  const stored = getTokens();
  if (stored === null) {
    clearTokens();
    notifySessionExpired();

    return false;
  }

  try {
    const response = await fetch(buildUrl("/auth/refresh"), {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: stored.refreshToken } satisfies RefreshTokenRequest),
    });

    if (!response.ok) {
      clearTokens();
      notifySessionExpired();

      return false;
    }

    const tokens = (await response.json()) as LoginResponse;
    setTokens({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken });

    return true;
  } catch {
    clearTokens();
    notifySessionExpired();

    return false;
  }
}
