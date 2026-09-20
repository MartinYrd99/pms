import { act, cleanup, render, screen } from "@testing-library/react";
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

function renderActivePage() {
  const testRouter = createMemoryRouter(routes, { initialEntries: ["/active"] });
  return render(<App router={testRouter} />);
}

const sessionOne = {
  id: 1,
  vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
  zone: { id: 10, name: "Blue Zone", city: "Sofia" },
  startedAt: "2026-09-20T09:59:00Z",
  endedAt: null,
  amount: null,
  paymentStatus: null,
};

const sessionTwo = {
  id: 2,
  vehicle: { id: 2, plate: "CA2222XX", brand: "Toyota", model: "Corolla" },
  zone: { id: 11, name: "Green Zone", city: "Sofia" },
  startedAt: "2026-09-20T08:00:00Z",
  endedAt: null,
  amount: null,
  paymentStatus: null,
};

function notFoundPaymentResponse(): Response {
  return jsonResponse(
    { code: "validation.payment.not-found", message: "No payment exists for the given parking session." },
    404,
  );
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  setSessionExpiredHandler(null);
  queryClient.clear();
});

describe("ActivePage", () => {
  it("renders two active sessions with vehicle, zone and a Sofia-local start time, and the elapsed time advances as the clock ticks", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-20T10:00:00Z"));

    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.endsWith("/parking-sessions/active")) {
        return jsonResponse([sessionOne, sessionTwo]);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderActivePage();

    // Two ticks: one for the boot-time restoreSession() refresh, one for the page's own fetch.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(screen.getByText("CA1111XX — VW Golf")).toBeInTheDocument();
    expect(screen.getByText("Blue Zone, Sofia")).toBeInTheDocument();
    expect(screen.getByText("Started 20/09/2026, 12:59:00")).toBeInTheDocument();
    expect(screen.getByText("CA2222XX — Toyota Corolla")).toBeInTheDocument();
    expect(screen.getByText("Green Zone, Sofia")).toBeInTheDocument();

    // Session one started exactly one minute before "now".
    expect(screen.getByText("00:01:00")).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });

    expect(screen.getByText("00:02:00")).toBeInTheDocument();
  });

  it("renders the empty state with a link to start a parking for a mocked empty active list", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.endsWith("/parking-sessions/active")) {
        return jsonResponse([]);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderActivePage();

    expect(await screen.findByText(/nothing parked right now/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /start a parking/i })).toHaveAttribute(
      "href",
      "/park",
    );
  });

  it("tapping End with a mocked 200 renders the end time and the returned amount", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/parking-sessions/active") && method === "GET") {
        return jsonResponse([sessionOne]);
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "GET") {
        return notFoundPaymentResponse();
      }
      if (url.endsWith("/parking-sessions/1/end") && method === "POST") {
        return jsonResponse({ ...sessionOne, endedAt: "2026-09-20T10:30:00Z", amount: 2 });
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderActivePage();

    await user.click(await screen.findByRole("button", { name: /end parking/i }));

    expect(await screen.findByText("Ended 20/09/2026, 13:30:00")).toBeInTheDocument();
    expect(screen.getByText("Amount: 2.00")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /end parking/i })).not.toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Pay" })).toBeInTheDocument();
    expect(screen.queryByText(/already been ended/i)).not.toBeInTheDocument();

    // A later refetch (e.g. window focus) must not make the just-shown bill disappear.
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ["parking-sessions", "active"] });
    });

    expect(screen.getByText("Ended 20/09/2026, 13:30:00")).toBeInTheDocument();
    expect(screen.getByText("Amount: 2.00")).toBeInTheDocument();
  });

  it("tapping End with a mocked 409 whose body is the ended session renders that same ended session and its amount, not a generic failure", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/parking-sessions/active") && method === "GET") {
        return jsonResponse([sessionOne]);
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "GET") {
        return notFoundPaymentResponse();
      }
      if (url.endsWith("/parking-sessions/1/end") && method === "POST") {
        return jsonResponse(
          {
            code: "validation.parking-session.already-ended",
            message: "This parking session was already ended.",
            session: { ...sessionOne, endedAt: "2026-09-20T10:30:00Z", amount: 2 },
          },
          409,
        );
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderActivePage();

    await user.click(await screen.findByRole("button", { name: /end parking/i }));

    expect(await screen.findByText("Ended 20/09/2026, 13:30:00")).toBeInTheDocument();
    expect(screen.getByText("Amount: 2.00")).toBeInTheDocument();
    expect(screen.getByText(/this parking had already been ended/i)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Pay" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    // A later refetch (e.g. window focus) must not make the just-shown bill disappear.
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ["parking-sessions", "active"] });
    });

    expect(screen.getByText("Ended 20/09/2026, 13:30:00")).toBeInTheDocument();
    expect(screen.getByText("Amount: 2.00")).toBeInTheDocument();
  });

  it("tapping Pay on the just-ended card posts the payment once and shows the pending state without leaving /active", async () => {
    const user = userEvent.setup();

    let postPaymentCalls = 0;
    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/parking-sessions/active") && method === "GET") {
        return jsonResponse([sessionOne]);
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "GET") {
        return notFoundPaymentResponse();
      }
      if (url.endsWith("/parking-sessions/1/end") && method === "POST") {
        return jsonResponse({ ...sessionOne, endedAt: "2026-09-20T10:30:00Z", amount: 2 });
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "POST") {
        postPaymentCalls += 1;
        return jsonResponse(
          {
            id: 100,
            sessionId: 1,
            amount: 2,
            status: "PENDING",
            createdAt: "2026-09-20T10:30:01Z",
            settledAt: null,
          },
          201,
        );
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderActivePage();

    await user.click(await screen.findByRole("button", { name: /end parking/i }));
    await user.click(await screen.findByRole("button", { name: "Pay" }));

    expect(await screen.findByText("Payment pending…")).toBeInTheDocument();
    expect(postPaymentCalls).toBe(1);
    // The bill and its card stay put: paying never navigates away from the home screen.
    expect(screen.getByText("Amount: 2.00")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /active parking/i })).toBeInTheDocument();
  });
});
