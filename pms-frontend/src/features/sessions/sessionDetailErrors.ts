import { isApiError } from "../../api";

/** Turns a caught get-session error into a message for the detail screen. */
export function mapSessionDetailError(error: unknown): string {
  if (!isApiError(error)) {
    return "Could not load this session. Please try again.";
  }

  if (error.status === 404) {
    return "Session not found.";
  }

  if (error.status === 403) {
    return "That session does not belong to you.";
  }

  return "Could not load this session. Please try again.";
}