import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { queryClient } from "../../app/queryClient";
import { routes } from "../../app/router";
import { setSessionExpiredHandler } from "../../api";
import { stubAuthenticatedFetch } from "../../test/apiFetchMock";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderParkPage() {
  const testRouter = createMemoryRouter(routes, { initialEntries: ["/park"] });
  return render(<App router={testRouter} />);
}

const vehicles = [{ id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" }];
const zones = [
  { id: 10, name: "Blue Zone", city: "Sofia", hourlyRate: 2, currency: "EUR", ruleType: "HOURLY" },
  { id: 11, name: "Green Zone", city: "Sofia", hourlyRate: 1.5, currency: "EUR", ruleType: "HOURLY" },
];

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  setSessionExpiredHandler(null);
  queryClient.clear();
});

describe("ParkPage", () => {
  it("renders zone options with name, city and rate, and vehicle options from the mocked lists", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.endsWith("/vehicles")) {
        return jsonResponse(vehicles);
      }
      if (url.endsWith("/zones")) {
        return jsonResponse(zones);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderParkPage();

    expect(
      await screen.findByRole("option", { name: "Blue Zone, Sofia — 2.00 EUR/h" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: "Green Zone, Sofia — 1.50 EUR/h" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /CA1111XX/ })).toBeInTheDocument();
  });

  it("posts the selected vehicle and zone and lands on the created session's detail screen", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/vehicles") && method === "GET") {
        return jsonResponse(vehicles);
      }
      if (url.endsWith("/zones") && method === "GET") {
        return jsonResponse(zones);
      }
      if (url.endsWith("/parking-sessions") && method === "POST") {
        const body = JSON.parse(String(init?.body)) as { vehicleId: number; zoneId: number };
        expect(body).toEqual({ vehicleId: 1, zoneId: 11 });

        return jsonResponse(
          {
            id: 42,
            vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
            zone: { id: 11, name: "Green Zone", city: "Sofia" },
            startedAt: "2026-09-20T10:00:00Z",
            endedAt: null,
            amount: null,
            paymentStatus: null,
          },
          201,
        );
      }
      if (url.endsWith("/parking-sessions/42") && method === "GET") {
        return jsonResponse({
          id: 42,
          vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
          zone: { id: 11, name: "Green Zone", city: "Sofia" },
          startedAt: "2026-09-20T10:00:00Z",
          endedAt: null,
          amount: null,
          paymentStatus: null,
        });
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderParkPage();

    await screen.findByRole("option", { name: "Blue Zone, Sofia — 2.00 EUR/h" });

    await user.selectOptions(screen.getByLabelText(/zone/i), "11");
    await user.click(screen.getByRole("button", { name: /start/i }));

    expect(await screen.findByText("CA1111XX — VW Golf")).toBeInTheDocument();
    expect(screen.getByText("Green Zone, Sofia")).toBeInTheDocument();
  });

  it("on a 409 with a blocking session, lands on that session and shows why", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/vehicles") && method === "GET") {
        return jsonResponse(vehicles);
      }
      if (url.endsWith("/zones") && method === "GET") {
        return jsonResponse(zones);
      }
      if (url.endsWith("/parking-sessions") && method === "POST") {
        return jsonResponse(
          {
            code: "validation.parking-session.unsettled-exists",
            message: "This vehicle already has an unsettled parking session.",
            blockingSessionId: 7,
          },
          409,
        );
      }
      if (url.endsWith("/parking-sessions/7") && method === "GET") {
        return jsonResponse({
          id: 7,
          vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
          zone: { id: 10, name: "Blue Zone", city: "Sofia" },
          startedAt: "2026-09-20T08:00:00Z",
          endedAt: null,
          amount: null,
          paymentStatus: null,
        });
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderParkPage();

    await screen.findByRole("option", { name: "Blue Zone, Sofia — 2.00 EUR/h" });

    await user.click(screen.getByRole("button", { name: /start/i }));

    expect(await screen.findByText(/already has an unsettled parking/i)).toBeInTheDocument();
    expect(screen.getByText("CA1111XX — VW Golf")).toBeInTheDocument();
    // Not a dead-end error screen: the actual session detail rendered underneath the reason.
    expect(screen.getByText("Blue Zone, Sofia")).toBeInTheDocument();
  });

  it("on a 409 with no blocking session id (the double-tap race), re-reads the active sessions and lands on the matching one", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/vehicles") && method === "GET") {
        return jsonResponse(vehicles);
      }
      if (url.endsWith("/zones") && method === "GET") {
        return jsonResponse(zones);
      }
      if (url.endsWith("/parking-sessions") && method === "POST") {
        return jsonResponse(
          {
            code: "validation.parking-session.unsettled-exists",
            message: "This vehicle already has an unsettled parking session.",
            blockingSessionId: null,
          },
          409,
        );
      }
      if (url.endsWith("/parking-sessions/active") && method === "GET") {
        return jsonResponse([
          {
            id: 9,
            vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
            zone: { id: 10, name: "Blue Zone", city: "Sofia" },
            startedAt: "2026-09-20T08:00:00Z",
            endedAt: null,
            amount: null,
            paymentStatus: null,
          },
        ]);
      }
      if (url.endsWith("/parking-sessions/9") && method === "GET") {
        return jsonResponse({
          id: 9,
          vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
          zone: { id: 10, name: "Blue Zone", city: "Sofia" },
          startedAt: "2026-09-20T08:00:00Z",
          endedAt: null,
          amount: null,
          paymentStatus: null,
        });
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderParkPage();

    await screen.findByRole("option", { name: "Blue Zone, Sofia — 2.00 EUR/h" });

    await user.click(screen.getByRole("button", { name: /start/i }));

    // Never a dead-end zone error: the loser of the race still lands on the real session.
    expect(await screen.findByText(/already has an unsettled parking/i)).toBeInTheDocument();
    expect(screen.getByText("CA1111XX — VW Golf")).toBeInTheDocument();
    expect(screen.getByText("Blue Zone, Sofia")).toBeInTheDocument();
  });

  it("on a 409 with no blocking session id, and the re-read finds nothing, still shows the unsettled message rather than a zone message", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/vehicles") && method === "GET") {
        return jsonResponse(vehicles);
      }
      if (url.endsWith("/zones") && method === "GET") {
        return jsonResponse(zones);
      }
      if (url.endsWith("/parking-sessions") && method === "POST") {
        return jsonResponse(
          {
            code: "validation.parking-session.unsettled-exists",
            message: "This vehicle already has an unsettled parking session.",
            blockingSessionId: null,
          },
          409,
        );
      }
      // The blocking session is ended-but-unpaid, so it never shows up in /active.
      if (url.endsWith("/parking-sessions/active") && method === "GET") {
        return jsonResponse([]);
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderParkPage();

    await screen.findByRole("option", { name: "Blue Zone, Sofia — 2.00 EUR/h" });

    await user.click(screen.getByRole("button", { name: /start/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/already has an unsettled parking/i);
    expect(screen.queryByText(/zone is no longer available/i)).not.toBeInTheDocument();
  });
});
