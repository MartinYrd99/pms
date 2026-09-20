import { isApiError } from "../../api";

/** Turns a caught pay/retry error into a message for the session screen. */
export function mapPayForParkingSessionError(error: unknown): string {
  if (isApiError(error) && error.status === 409) {
    return "This session is still active. End it before paying.";
  }

  return "Could not start the payment. Please try again.";
}