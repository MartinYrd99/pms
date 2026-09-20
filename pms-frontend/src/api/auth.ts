import { apiRequest } from "./client";
import { clearTokens, getTokens, setTokens } from "./tokenStorage";
import type {
  LoginRequest,
  LoginResponse,
  RefreshTokenRequest,
  RegisterRequest,
  RegisterResponse,
} from "./types/auth";

export async function register(request: RegisterRequest): Promise<RegisterResponse> {
  return apiRequest<RegisterResponse, RegisterRequest>({
    method: "POST",
    path: "/auth/register",
    body: request,
    auth: false,
  });
}

/** Stores the issued pair so every later call is authenticated. */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const tokens = await apiRequest<LoginResponse, LoginRequest>({
    method: "POST",
    path: "/auth/login",
    body: request,
    auth: false,
  });
  setTokens(tokens);
  return tokens;
}

export async function refresh(request: RefreshTokenRequest): Promise<LoginResponse> {
  const tokens = await apiRequest<LoginResponse, RefreshTokenRequest>({
    method: "POST",
    path: "/auth/refresh",
    body: request,
    auth: false,
  });
  setTokens(tokens);
  return tokens;
}

/** Always forgets this device's tokens locally, even if the server call itself fails. */
export async function logout(): Promise<void> {
  const stored = getTokens();
  try {
    if (stored !== null) {
      await apiRequest<void, RefreshTokenRequest>({
        method: "POST",
        path: "/auth/logout",
        body: { refreshToken: stored.refreshToken },
      });
    }
  } finally {
    clearTokens();
  }
}