import { isApiError } from "../../api";

/** Turns a caught error into readable text — the backend's own message for a known ApiError, a generic fallback otherwise; never a raw error object on screen. */
export function extractApiErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    return error.message;
  }

  return "Something went wrong. Please try again.";
}