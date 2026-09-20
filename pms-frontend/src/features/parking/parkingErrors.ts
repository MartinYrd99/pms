import { isApiError } from "../../api";
import type { SessionConflictResponse } from "../../api";

// Mirrors the codes ParkingSessionService actually throws (backend ParkingSessionService.java).
const UNSETTLED_SESSION_CODE = "validation.parking-session.unsettled-exists";
const ZONE_NOT_FOUND_CODE = "validation.zone.not-found";
const ZONE_INACTIVE_CODE = "validation.zone.inactive";

export interface StartParkingErrorResult {
  /**
   * "blocked" is the 409 that names the session already occupying this vehicle; the caller must
   * navigate there. "unsettled-unknown" is the same conflict but racing another start for the same
   * vehicle left the blocking session's id unknown; the caller must re-read to find it.
   */
  kind: "blocked" | "unsettled-unknown" | "message";
  message: string;
  blockingSessionId?: number;
}

/**
 * Turns a caught start-parking error into either a navigation instruction (the vehicle already
 * has an unsettled session — go to it, or re-read for it if two starts raced) or a message to show
 * on the form. Discriminated by the backend's error `code`, not by payload shape, since the
 * unsettled-session 409 can carry a null blockingSessionId.
 */
export function mapStartParkingError(error: unknown): StartParkingErrorResult {
  if (!isApiError(error)) {
    return { kind: "message", message: "Something went wrong. Please try again." };
  }

  if (error.status === 409 && error.code === UNSETTLED_SESSION_CODE) {
    const message = "This vehicle already has an unsettled parking.";
    const blockingSessionId = extractBlockingSessionId(error.body);

    return blockingSessionId === null
      ? { kind: "unsettled-unknown", message }
      : { kind: "blocked", message, blockingSessionId };
  }

  if (error.status === 403) {
    return { kind: "message", message: "That vehicle is not yours." };
  }

  if (
    error.status === 404 ||
    (error.status === 409 && (error.code === ZONE_NOT_FOUND_CODE || error.code === ZONE_INACTIVE_CODE))
  ) {
    return {
      kind: "message",
      message: "That zone is no longer available. Pick another zone and try again.",
    };
  }

  return { kind: "message", message: error.message };
}

function extractBlockingSessionId(body: unknown): number | null {
  if (typeof body !== "object" || body === null) {
    return null;
  }

  const value = (body as SessionConflictResponse).blockingSessionId;

  return typeof value === "number" ? value : null;
}
