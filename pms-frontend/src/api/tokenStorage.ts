// The access token lives only in memory: it is re-minted from the HttpOnly refresh cookie on
// every app boot and dies on reload, so it can never be read out of durable, script-accessible
// storage.
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string): void {
  accessToken = token;
}

export function clearAccessToken(): void {
  accessToken = null;
}
