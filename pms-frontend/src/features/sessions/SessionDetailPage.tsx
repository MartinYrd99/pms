import { useQuery } from "@tanstack/react-query";
import { useLocation, useParams } from "react-router";
import { getParkingSession } from "../../api";
import { mapSessionDetailError } from "./sessionDetailErrors";
import "./SessionDetailPage.css";

interface SessionDetailLocationState {
  /** Set when the router landed here off the 409 "vehicle already unsettled" rule from /park. */
  blockedReason?: string;
}

function formatDateTime(value: string | null): string {
  return value === null ? "—" : new Date(value).toLocaleString();
}

function formatAmount(amount: number | null): string {
  return amount === null ? "—" : amount.toFixed(2);
}

/**
 * Minimal read-only view of one parking session: vehicle, zone, times, amount and payment status
 * as the API returns them. The active-session experience (elapsed timer, End) is a later ticket.
 */
function SessionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const sessionId = Number(id);
  const locationState = location.state as SessionDetailLocationState | null;

  const sessionQuery = useQuery({
    queryKey: ["parking-session", sessionId],
    queryFn: () => getParkingSession(sessionId),
    enabled: !Number.isNaN(sessionId),
  });

  return (
    <section className="session-detail-page">
      <h2>Parking session</h2>

      {locationState?.blockedReason && (
        <p role="status" className="session-detail-page__reason">
          {locationState.blockedReason}
        </p>
      )}

      {Number.isNaN(sessionId) && (
        <p role="alert" className="session-detail-page__status session-detail-page__error">
          Session not found.
        </p>
      )}

      {sessionQuery.isLoading && (
        <p className="session-detail-page__status">Loading session…</p>
      )}

      {sessionQuery.isError && (
        <p role="alert" className="session-detail-page__status session-detail-page__error">
          {mapSessionDetailError(sessionQuery.error)}
        </p>
      )}

      {sessionQuery.isSuccess && (
        <dl className="session-detail-page__details">
          <dt>Vehicle</dt>
          <dd>
            {sessionQuery.data.vehicle.plate} — {sessionQuery.data.vehicle.brand}{" "}
            {sessionQuery.data.vehicle.model}
          </dd>

          <dt>Zone</dt>
          <dd>
            {sessionQuery.data.zone.name}, {sessionQuery.data.zone.city}
          </dd>

          <dt>Started</dt>
          <dd>{formatDateTime(sessionQuery.data.startedAt)}</dd>

          <dt>Ended</dt>
          <dd>{formatDateTime(sessionQuery.data.endedAt)}</dd>

          <dt>Amount</dt>
          <dd>{formatAmount(sessionQuery.data.amount)}</dd>

          <dt>Payment status</dt>
          <dd>{sessionQuery.data.paymentStatus ?? "Not paid yet"}</dd>
        </dl>
      )}
    </section>
  );
}

export default SessionDetailPage;