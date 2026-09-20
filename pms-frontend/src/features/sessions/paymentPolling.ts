/** Query key for one session's live payment status, polled independently of the session detail. */
export function paymentQueryKey(sessionId: number): readonly [string, number, string] {
  return ["parking-session", sessionId, "payment"] as const;
}

/** Backoff schedule: 2s for the first three polls, 5s for the next three, then 10s onward. */
function pollDelayMsForCount(pollCount: number): number {
  if (pollCount <= 3) {
    return 2_000;
  }

  if (pollCount <= 6) {
    return 5_000;
  }

  return 10_000;
}

/**
 * Polling stops for good once ~2 minutes have passed since it began — a stalled settlement job
 * degrades the app to "slow", never an infinite loop of requests.
 */
export const PAYMENT_POLL_MAX_ELAPSED_MS = 120_000;

/**
 * Delay before the next payment-status poll, or `false` once scheduling another one would cross the
 * 2-minute ceiling — at that point the caller stops polling for good and falls back to a manual,
 * user-driven re-read instead.
 */
export function nextPaymentPollDelayMs(pollCount: number, elapsedMs: number): number | false {
  const delay = pollDelayMsForCount(pollCount);

  return elapsedMs + delay <= PAYMENT_POLL_MAX_ELAPSED_MS ? delay : false;
}