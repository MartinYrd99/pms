import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { getParkingSessionPayment, isApiError, payForParkingSession } from "../../api";
import { mapPayForParkingSessionError } from "./paymentErrors";
import { nextPaymentPollDelayMs, paymentQueryKey } from "./paymentPolling";
import "./PaymentSection.css";

const ACTIVE_SESSIONS_QUERY_KEY = ["parking-sessions", "active"];
const PARKING_HISTORY_QUERY_KEY = ["parking-sessions", "history"];

interface PaymentSectionProps {
  sessionId: number;
}

interface PollTracker {
  /** When this tracker last advanced — a successful read or a failed one, whichever landed last. */
  lastSeenAt: number;
  /** How many reads (successful or failed) have settled since watching this payment attempt began. */
  count: number;
  /** When the first PENDING read of this attempt landed — the clock the 2-minute ceiling counts from. */
  startedAt: number;
}

/**
 * The session screen's Pay action and its bounded payment-status poller. Submits the payment, then
 * watches its status with a fixed 2s/5s/10s backoff that gives up after ~2 minutes, so a stalled
 * settlement job degrades the app to slow rather than flooding it with requests.
 */
function PaymentSection({ sessionId }: PaymentSectionProps) {
  const queryClient = useQueryClient();

  // How many reads (successful or failed) have landed for the current attempt, and when it started.
  const [tracker, setTracker] = useState<PollTracker | null>(null);

  const paymentQuery = useQuery({
    queryKey: paymentQueryKey(sessionId),
    queryFn: () => getParkingSessionPayment(sessionId),
    // A 404 ("never paid") is a normal outcome here, not a transient failure to retry into.
    retry: false,
    refetchInterval:
      tracker === null ? false : nextPaymentPollDelayMs(tracker.count, tracker.lastSeenAt - tracker.startedAt),
  });

  const status = paymentQuery.data?.status ?? null;
  const dataUpdatedAt = paymentQuery.dataUpdatedAt;
  // A failed poll (500, timeout, offline) keeps the previous PENDING data and leaves `dataUpdatedAt`
  // frozen, so the attempt clock has to be anchored to whichever of a success or a failure landed
  // most recently — otherwise a failing read locks the backoff at its fastest tier forever instead
  // of ever reaching the 2-minute ceiling.
  const lastSettledAt = Math.max(dataUpdatedAt, paymentQuery.errorUpdatedAt);

  if (status === "PENDING" && tracker === null) {
    setTracker({ lastSeenAt: dataUpdatedAt, count: 1, startedAt: dataUpdatedAt });
  } else if (status === "PENDING" && tracker !== null && lastSettledAt !== tracker.lastSeenAt) {
    setTracker({ lastSeenAt: lastSettledAt, count: tracker.count + 1, startedAt: tracker.startedAt });
  } else if (status !== "PENDING" && tracker !== null) {
    setTracker(null);
  }

  // COMPLETED here means the rest of the app is now stale: the active list drops the session and
  // the (future) history list should show it as paid.
  useEffect(() => {
    if (status !== "COMPLETED") {
      return;
    }

    void queryClient.invalidateQueries({ queryKey: ACTIVE_SESSIONS_QUERY_KEY });
    void queryClient.invalidateQueries({ queryKey: PARKING_HISTORY_QUERY_KEY });
  }, [status, queryClient]);

  const payMutation = useMutation({
    mutationFn: () => payForParkingSession(sessionId),
    onSuccess: (payment) => {
      queryClient.setQueryData(paymentQueryKey(sessionId), payment);
    },
  });

  function renderPayButton(label: string) {
    return (
      <>
        <button type="button" onClick={() => payMutation.mutate()} disabled={payMutation.isPending}>
          {payMutation.isPending ? "Paying…" : label}
        </button>
        {payMutation.isError && (
          <p role="alert" className="payment-section__error">
            {mapPayForParkingSessionError(payMutation.error)}
          </p>
        )}
      </>
    );
  }

  if (paymentQuery.isLoading) {
    return <p className="payment-section__status">Checking payment status…</p>;
  }

  if (paymentQuery.data === undefined) {
    // No payment has ever been read successfully yet, so there is no stale status to fall back on.
    if (paymentQuery.isError) {
      if (isApiError(paymentQuery.error) && paymentQuery.error.status === 404) {
        return <div className="payment-section">{renderPayButton("Pay")}</div>;
      }

      return (
        <div className="payment-section">
          <p role="alert" className="payment-section__error">
            Could not check the payment status. Please try again.
          </p>
          <button type="button" onClick={() => void paymentQuery.refetch()}>
            Check again
          </button>
        </div>
      );
    }

    return <p className="payment-section__status">Checking payment status…</p>;
  }

  // From here on a payment has been read at least once, so a poll that is currently failing (the
  // status still says PENDING from the last successful read) must not be shown as if watching had
  // stopped — it hasn't: the poller keeps counting failed attempts toward the same 2-minute ceiling.
  const payment = paymentQuery.data;

  if (payment.status === "COMPLETED") {
    return <p className="payment-section__status">Paid</p>;
  }

  if (payment.status === "FAILED") {
    return (
      <div className="payment-section">
        <p role="alert" className="payment-section__error">
          Payment failed.
        </p>
        {renderPayButton("Retry")}
      </div>
    );
  }

  // PENDING: still watching, unless the 2-minute ceiling has already been reached for this attempt.
  // Uses the tracker's own clock (not `dataUpdatedAt`) so a run of failed polls is weighed in too.
  const stillWatching =
    tracker !== null && nextPaymentPollDelayMs(tracker.count, tracker.lastSeenAt - tracker.startedAt) !== false;

  if (stillWatching) {
    return <p className="payment-section__status">Payment pending…</p>;
  }

  return (
    <div className="payment-section">
      <p role="status" className="payment-section__status">
        Payment is still processing — check history.
      </p>
      <button type="button" onClick={() => void paymentQuery.refetch()}>
        Check again
      </button>
    </div>
  );
}

export default PaymentSection;
