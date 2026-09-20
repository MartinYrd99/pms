import { useMutation, useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { listActiveParkingSessions, listVehicles, listZones, startParkingSession } from "../../api";
import type { ZoneResponse } from "../../api";
import { mapStartParkingError } from "./parkingErrors";
import "./ParkPage.css";

const VEHICLES_QUERY_KEY = ["vehicles"];
const ZONES_QUERY_KEY = ["zones"];

/** One line per zone so the rate is visible before Start is ever tapped. */
function formatZoneOption(zone: ZoneResponse): string {
  return `${zone.name}, ${zone.city} — ${zone.hourlyRate.toFixed(2)} ${zone.currency}/h`;
}

/** Lets the driver pick their vehicle and an active zone, see the rate, and start a parking session. */
function ParkPage() {
  const vehicleFieldId = useId();
  const zoneFieldId = useId();
  const navigate = useNavigate();

  const [vehicleId, setVehicleId] = useState<number | null>(null);
  const [zoneId, setZoneId] = useState<number | null>(null);
  // Fallback for the double-tap race: the 409 named no blocking session id, the re-read below
  // could not find it either, so this is the only thing left to tell the driver.
  const [unresolvedBlockMessage, setUnresolvedBlockMessage] = useState<string | null>(null);

  const vehiclesQuery = useQuery({ queryKey: VEHICLES_QUERY_KEY, queryFn: listVehicles });
  const zonesQuery = useQuery({ queryKey: ZONES_QUERY_KEY, queryFn: listZones });

  const startMutation = useMutation({
    mutationFn: (request: { vehicleId: number; zoneId: number }) => startParkingSession(request),
    onMutate: () => {
      setUnresolvedBlockMessage(null);
    },
    onSuccess: (session) => {
      navigate(`/sessions/${session.id}`);
    },
    onError: (error, variables) => {
      const result = mapStartParkingError(error);

      if (result.kind === "blocked" && result.blockingSessionId !== undefined) {
        navigate(`/sessions/${result.blockingSessionId}`, {
          state: { blockedReason: result.message },
        });
        return;
      }

      if (result.kind === "unsettled-unknown") {
        void resolveUnsettledSession(variables.vehicleId, result.message);
      }
    },
  });

  // The losing tap of two concurrent starts gets a 409 with no blocking session id (the database's
  // unique constraint fired before the winning session could be looked up), so re-read the user's
  // active sessions and find the one for this vehicle instead of redoing the start.
  async function resolveUnsettledSession(startedVehicleId: number, message: string) {
    try {
      const activeSessions = await listActiveParkingSessions();
      const blockingSession = activeSessions.find(
        (session) => session.vehicle.id === startedVehicleId,
      );

      if (blockingSession !== undefined) {
        navigate(`/sessions/${blockingSession.id}`, { state: { blockedReason: message } });
        return;
      }
    } catch {
      // Re-read failed too (or the blocking session was ended-but-unpaid and so isn't "active");
      // fall through to the fallback message below rather than leaving the driver with nothing.
    }

    setUnresolvedBlockMessage(message);
  }

  const selectedVehicleId = vehicleId ?? vehiclesQuery.data?.[0]?.id ?? null;
  const selectedZoneId = zoneId ?? zonesQuery.data?.[0]?.id ?? null;

  const startErrorResult = startMutation.isError
    ? mapStartParkingError(startMutation.error)
    : null;
  const formError =
    startErrorResult?.kind === "message" ? startErrorResult.message : unresolvedBlockMessage;

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (selectedVehicleId === null || selectedZoneId === null) {
      return;
    }

    startMutation.mutate({ vehicleId: selectedVehicleId, zoneId: selectedZoneId });
  }

  const hasNoVehicles = vehiclesQuery.isSuccess && vehiclesQuery.data.length === 0;
  const hasNoZones = zonesQuery.isSuccess && zonesQuery.data.length === 0;
  const canSubmit =
    !hasNoVehicles && !hasNoZones && selectedVehicleId !== null && selectedZoneId !== null;

  return (
    <section className="park-page">
      <h2>Start parking</h2>

      {(vehiclesQuery.isLoading || zonesQuery.isLoading) && (
        <p className="park-page__status">Loading vehicles and zones…</p>
      )}

      {vehiclesQuery.isError && (
        <p role="alert" className="park-page__status park-page__error">
          Could not load your vehicles. Please try again.
        </p>
      )}
      {zonesQuery.isError && (
        <p role="alert" className="park-page__status park-page__error">
          Could not load parking zones. Please try again.
        </p>
      )}

      {hasNoVehicles && (
        <p className="park-page__status">
          You have no vehicles yet. <Link to="/vehicles">Register one first</Link>.
        </p>
      )}
      {hasNoZones && (
        <p className="park-page__status">No parking zones are available right now.</p>
      )}

      {vehiclesQuery.isSuccess &&
        zonesQuery.isSuccess &&
        !hasNoVehicles &&
        !hasNoZones && (
          <form className="park-form" onSubmit={handleSubmit}>
            <div className="park-form__field">
              <label htmlFor={vehicleFieldId}>Vehicle</label>
              <select
                id={vehicleFieldId}
                name="vehicleId"
                value={selectedVehicleId ?? ""}
                onChange={(event) => setVehicleId(Number(event.target.value))}
              >
                {vehiclesQuery.data.map((vehicle) => (
                  <option key={vehicle.id} value={vehicle.id}>
                    {vehicle.plate} — {vehicle.brand} {vehicle.model}
                  </option>
                ))}
              </select>
            </div>

            <div className="park-form__field">
              <label htmlFor={zoneFieldId}>Zone</label>
              <select
                id={zoneFieldId}
                name="zoneId"
                value={selectedZoneId ?? ""}
                onChange={(event) => setZoneId(Number(event.target.value))}
              >
                {zonesQuery.data.map((zone) => (
                  <option key={zone.id} value={zone.id}>
                    {formatZoneOption(zone)}
                  </option>
                ))}
              </select>
            </div>

            {formError && (
              <p role="alert" className="park-form__error">
                {formError}
              </p>
            )}

            <button type="submit" disabled={!canSubmit || startMutation.isPending}>
              {startMutation.isPending ? "Starting…" : "Start"}
            </button>
          </form>
        )}
    </section>
  );
}

export default ParkPage;