import { apiRequest } from "./client";
import { clearAccessToken, setAccessToken } from "./tokenStorage";
import type { AccessTokenResponse, LoginRequest, RegisterRequest, RegisterResponse } from "./types/auth";

export async function register(request: RegisterRequest): Promise<RegisterResponse> {
  return apiRequest<RegisterResponse, RegisterRequest>({
    method: "POST",
    path: "/auth/register",
    body: request,
    auth: false,
  });
}

/** Stores the issued access token in memory; the refresh token never reaches JavaScript at all,
 * it rides in as the HttpOnly cookie the server sets alongside this response. */
export async function login(request: LoginRequest): Promise<AccessTokenResponse> {
  const tokens = await apiRequest<AccessTokenResponse, LoginRequest>({
    method: "POST",
    path: "/auth/login",
    body: request,
    auth: false,
  });
  setAccessToken(tokens.accessToken);
  return tokens;
}

/** Refreshes from the HttpOnly cookie alone: no body to send and no stored refresh token to read. */
export async function refresh(): Promise<AccessTokenResponse> {
  const tokens = await apiRequest<AccessTokenResponse, undefined>({
    method: "POST",
    path: "/auth/refresh",
    auth: false,
  });
  setAccessToken(tokens.accessToken);

  return tokens;
}

/** Always forgets this device's access token locally, even if the server call itself fails. */
export async function logout(): Promise<void> {
  try {
    await apiRequest<void, undefined>({
      method: "POST",
      path: "/auth/logout",
    });
  } finally {
    clearAccessToken();
  }
}
