import { endParkingSession, isApiError } from "../../api";
import type { ParkingSessionResponse, SessionAlreadyEndedResponse } from "../../api";

export interface EndSessionResult {
  session: ParkingSessionResponse;
  /** True when the 409 "already ended" case fired — the app shows the same session, quietly. */
  alreadyEnded: boolean;
}

/**
 * Ends a session, treating the "already ended" 409 as a re-read rather than a failure: the body is
 * the ended session with its amount, so there is nothing to redo, only to show.
 */
export async function endSessionIdempotently(sessionId: number): Promise<EndSessionResult> {
  try {
    const session = await endParkingSession(sessionId);

    return { session, alreadyEnded: false };
  } catch (error) {
    if (isApiError<SessionAlreadyEndedResponse>(error) && error.status === 409) {
      return { session: error.body.session, alreadyEnded: true };
    }

    throw error;
  }
}

export function mapEndSessionError(error: unknown): string {
  if (isApiError(error) && error.status === 404) {
    return "This parking session was not found.";
  }

  if (isApiError(error) && error.status === 403) {
    return "That parking session is not yours.";
  }

  return "Could not end parking. Please try again.";
}