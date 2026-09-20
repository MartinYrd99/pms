/**
 * Turns a caught list-history error into a message for the history screen. The endpoint is always
 * scoped to the signed-in user, so there is no 403/404 to special-case here — any failure (network,
 * 401 exhausted, 5xx) reduces to the same "try again" message.
 */
export function mapParkingHistoryError(_error: unknown): string {
  return "Could not load your parking history. Please try again.";
}