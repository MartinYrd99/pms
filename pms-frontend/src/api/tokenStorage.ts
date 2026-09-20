export interface StoredTokens {
  accessToken: string;
  refreshToken: string;
}

// Single localStorage key for both tokens, so the 48h refresh token survives a reload.
const STORAGE_KEY = "pms.auth.tokens";

export function getTokens(): StoredTokens | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (raw === null) {
    return null;
  }

  try {
    return JSON.parse(raw) as StoredTokens;
  } catch {
    return null;
  }
}

export function getAccessToken(): string | null {
  return getTokens()?.accessToken ?? null;
}

export function setTokens(tokens: StoredTokens): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(tokens));
}

export function clearTokens(): void {
  localStorage.removeItem(STORAGE_KEY);
}

/** Lets a route guard ask "is anyone logged in" without ever reading the tokens themselves. */
export function hasStoredSession(): boolean {
  return getTokens() !== null;
}
