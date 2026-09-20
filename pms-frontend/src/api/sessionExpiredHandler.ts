type SessionExpiredHandler = () => void;

let handler: SessionExpiredHandler | null = null;

/** Registered once by the login screen so a dead refresh token sends the user back to login. */
export function setSessionExpiredHandler(next: SessionExpiredHandler | null): void {
  handler = next;
}

export function notifySessionExpired(): void {
  handler?.();
}