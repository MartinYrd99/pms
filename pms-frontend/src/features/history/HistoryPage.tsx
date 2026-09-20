import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { listParkingHistory } from "../../api";
import type { PaymentStatus } from "../../api";
import { formatSofiaDateTime } from "../../shared/formatting/sofiaDateTime";
import { mapParkingHistoryError } from "./historyErrors";
import "./HistoryPage.css";

const PAGE_SIZE = 20;

function historyQueryKey(page: number): readonly [string, string, number] {
  return ["parking-sessions", "history", page] as const;
}

/** Falls back to page 0 for a missing or malformed `?page=` — a reload never lands on a broken page. */
function parsePage(searchParams: URLSearchParams): number {
  const raw = Number(searchParams.get("page"));

  return Number.isInteger(raw) && raw >= 0 ? raw : 0;
}

function formatAmount(amount: number | null): string {
  return amount === null ? "—" : `${amount.toFixed(2)} EUR`;
}

function formatPaymentStatus(status: PaymentStatus | null): string {
  switch (status) {
    case "COMPLETED":
      return "Paid";
    case "PENDING":
      return "Pending";
    case "FAILED":
      return "Failed";
    default:
      return "Not paid";
  }
}

/** The signed-in driver's own ended parkings, newest first, offset-paginated straight over the API. */
function HistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = parsePage(searchParams);

  const historyQuery = useQuery({
    queryKey: historyQueryKey(page),
    queryFn: () => listParkingHistory({ page, size: PAGE_SIZE }),
  });

  // There is no totalPages on the wire, so "last page" is derived from the count and page size.
  const isLastPage = historyQuery.isSuccess
    ? (page + 1) * historyQuery.data.size >= historyQuery.data.totalElements
    : true;

  function goToPage(targetPage: number) {
    setSearchParams({ page: String(targetPage) });
  }

  return (
    <section className="history-page">
      <h2>Parking history</h2>

      {historyQuery.isLoading && (
        <p className="history-page__status">Loading your parking history…</p>
      )}

      {historyQuery.isError && (
        <p role="alert" className="history-page__status history-page__error">
          {mapParkingHistoryError(historyQuery.error)}
        </p>
      )}

      {historyQuery.isSuccess && historyQuery.data.content.length === 0 && (
        <p className="history-page__status">No parkings yet.</p>
      )}

      {historyQuery.isSuccess && historyQuery.data.content.length > 0 && (
        <>
          <table className="history-page__table">
            <thead>
              <tr>
                <th scope="col">Vehicle</th>
                <th scope="col">Zone</th>
                <th scope="col">Started</th>
                <th scope="col">Ended</th>
                <th scope="col">Amount</th>
                <th scope="col">Payment</th>
              </tr>
            </thead>
            <tbody>
              {historyQuery.data.content.map((session) => (
                <tr key={session.id}>
                  <td>
                    <Link to={`/sessions/${session.id}`}>
                      {session.vehicle.plate} — {session.vehicle.brand} {session.vehicle.model}
                    </Link>
                  </td>
                  <td>
                    {session.zone.name}, {session.zone.city}
                  </td>
                  <td>{formatSofiaDateTime(session.startedAt)}</td>
                  <td>{session.endedAt !== null ? formatSofiaDateTime(session.endedAt) : "—"}</td>
                  <td>{formatAmount(session.amount)}</td>
                  <td>{formatPaymentStatus(session.paymentStatus)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <nav className="history-page__pagination" aria-label="History pages">
            <button type="button" onClick={() => goToPage(page - 1)} disabled={page === 0}>
              Previous
            </button>
            <span className="history-page__page-indicator">Page {page + 1}</span>
            <button type="button" onClick={() => goToPage(page + 1)} disabled={isLastPage}>
              Next
            </button>
          </nav>
        </>
      )}
    </section>
  );
}

export default HistoryPage;