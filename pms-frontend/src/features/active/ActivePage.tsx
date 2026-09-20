import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { listActiveParkingSessions } from "../../api";
import type { ParkingSessionResponse } from "../../api";
import { formatSofiaDateTime } from "../../shared/formatting/sofiaDateTime";
import { endSessionIdempotently, mapEndSessionError } from "./endSession";
import type { EndSessionResult } from "./endSession";
import { formatElapsedDuration, useElapsedSeconds } from "./useElapsedSeconds";
import "./ActivePage.css";

const ACTIVE_SESSIONS_QUERY_KEY = ["parking-sessions", "active"];

interface DisplayedSession {
  session: ParkingSessionResponse;
  alreadyEnded: boolean;
}

function formatAmount(amount: number): string {
  return amount.toFixed(2);
}

interface ActiveSessionCardProps {
  session: ParkingSessionResponse;
  alreadyEnded: boolean;
  onEnded: (sessionId: number, result: EndSessionResult) => void;
}

/** One running (or just-ended) parking, with its own elapsed timer and End action. */
function ActiveSessionCard({ session, alreadyEnded, onEnded }: ActiveSessionCardProps) {
  const elapsedSeconds = useElapsedSeconds(session.startedAt);
  const isEnded = session.endedAt !== null;

  const endMutation = useMutation({
    mutationFn: () => endSessionIdempotently(session.id),
    onSuccess: (result) => onEnded(session.id, result),
  });

  return (
    <li className="active-page__card">
      <p className="active-page__vehicle">
        {session.vehicle.plate} — {session.vehicle.brand} {session.vehicle.model}
      </p>
      <p className="active-page__zone">
        {session.zone.name}, {session.zone.city}
      </p>
      <p className="active-page__started">Started {formatSofiaDateTime(session.startedAt)}</p>

      {!isEnded && (
        <>
          <p className="active-page__elapsed">{formatElapsedDuration(elapsedSeconds)}</p>
          <button
            type="button"
            onClick={() => endMutation.mutate()}
            disabled={endMutation.isPending}
          >
            {endMutation.isPending ? "Ending…" : "End parking"}
          </button>
          {endMutation.isError && (
            <p role="alert" className="active-page__error">
              {mapEndSessionError(endMutation.error)}
            </p>
          )}
        </>
      )}

      {isEnded && (
        <div className="active-page__ended">
          {alreadyEnded && (
            <p role="status" className="active-page__note">
              This parking had already been ended.
            </p>
          )}
          <p className="active-page__ended-at">
            Ended {session.endedAt !== null ? formatSofiaDateTime(session.endedAt) : "—"}
          </p>
          <p className="active-page__amount">
            Amount: {session.amount !== null ? formatAmount(session.amount) : "—"}
          </p>
        </div>
      )}
    </li>
  );
}

/** The signed-in driver's home screen: every parking currently running, each with a local timer and an End action. */
function ActivePage() {
  const queryClient = useQueryClient();
  const activeSessionsQuery = useQuery({
    queryKey: ACTIVE_SESSIONS_QUERY_KEY,
    queryFn: listActiveParkingSessions,
  });

  // Ended sessions live here, not in the query cache: once a session ends, /active stops returning
  // it, so the just-computed bill must survive the next refetch (window focus, remount) rather than
  // being unmounted along with a card that's no longer in the list.
  const [endedSessions, setEndedSessions] = useState<Map<number, DisplayedSession>>(new Map());

  function handleEnded(sessionId: number, result: EndSessionResult) {
    setEndedSessions((previous) => {
      const next = new Map(previous);

      next.set(sessionId, { session: result.session, alreadyEnded: result.alreadyEnded });

      return next;
    });

    void queryClient.invalidateQueries({ queryKey: ACTIVE_SESSIONS_QUERY_KEY });
  }

  const displayedSessions: DisplayedSession[] = [
    ...(activeSessionsQuery.data ?? [])
      .filter((session) => !endedSessions.has(session.id))
      .map((session) => ({ session, alreadyEnded: false })),
    ...endedSessions.values(),
  ];

  return (
    <section className="active-page">
      <h2>Active parking</h2>

      {activeSessionsQuery.isLoading && (
        <p className="active-page__status">Loading your active parking…</p>
      )}

      {activeSessionsQuery.isError && (
        <p role="alert" className="active-page__status active-page__error">
          Could not load your active parking. Please try again.
        </p>
      )}

      {activeSessionsQuery.isSuccess && displayedSessions.length === 0 && (
        <p className="active-page__status">
          Nothing parked right now. <Link to="/park">Start a parking</Link>.
        </p>
      )}

      {activeSessionsQuery.isSuccess && displayedSessions.length > 0 && (
        <ul className="active-page__list">
          {displayedSessions.map(({ session, alreadyEnded }) => (
            <ActiveSessionCard
              key={session.id}
              session={session}
              alreadyEnded={alreadyEnded}
              onEnded={handleEnded}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

export default ActivePage;