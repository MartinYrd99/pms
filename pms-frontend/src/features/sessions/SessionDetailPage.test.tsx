import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
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

function renderSessionDetailPage() {
  const testRouter = createMemoryRouter(routes, { initialEntries: ["/sessions/1"] });
  return render(<App router={testRouter} />);
}

/**
 * Settles chained fetch → render → fetch flows under fake timers (e.g. the session detail fetch
 * resolving, mounting the payment section, which then fetches on its own). A 1ms nudge per step is
 * used rather than 0: jsdom's Response body reading needs the fake clock to actually move forward
 * to unblock, where advancing by exactly 0 leaves it stuck mid-flight.
 */
async function flushMicrotasks() {
  for (let i = 0; i < 10; i += 1) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
  }
}

const endedSession = {
  id: 1,
  vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
  zone: { id: 10, name: "Blue Zone", city: "Sofia" },
  startedAt: "2026-09-20T08:00:00Z",
  endedAt: "2026-09-20T10:00:00Z",
  amount: 4,
  paymentStatus: null,
};

const paymentBase = {
  id: 100,
  sessionId: 1,
  amount: 4,
  createdAt: "2026-09-20T10:00:00Z",
  settledAt: null,
};

function notFoundResponse(): Response {
  return jsonResponse({ code: "error.not-found", message: "No payment for this session." }, 404);
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  setSessionExpiredHandler(null);
  queryClient.clear();
});

describe("SessionDetailPage payment", () => {
  it("tapping Pay posts the payment once, shows the pending state, and a mocked COMPLETED response shows Paid and makes no further status requests", async () => {
    vi.useFakeTimers();

    let getPaymentCalls = 0;
    let postPaymentCalls = 0;
    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/parking-sessions/1") && method === "GET") {
        return jsonResponse(endedSession);
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "GET") {
        getPaymentCalls += 1;
        if (getPaymentCalls === 1) {
          return notFoundResponse();
        }
        return jsonResponse({ ...paymentBase, status: "COMPLETED", settledAt: "2026-09-20T10:00:02Z" });
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "POST") {
        postPaymentCalls += 1;
        return jsonResponse({ ...paymentBase, status: "PENDING" }, 201);
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderSessionDetailPage();
    await flushMicrotasks();

    fireEvent.click(screen.getByRole("button", { name: /^pay$/i }));
    await flushMicrotasks();

    expect(screen.getByText("Payment pending…")).toBeInTheDocument();
    expect(postPaymentCalls).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });

    expect(screen.getByText("Paid")).toBeInTheDocument();
    expect(getPaymentCalls).toBe(2);
    expect(postPaymentCalls).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });

    expect(getPaymentCalls).toBe(2);
  });

  it("polls a stuck PENDING status with a 2s/5s/10s backoff and stops after ~2 minutes, showing the still-processing message", async () => {
    vi.useFakeTimers();

    let getPaymentCalls = 0;
    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/parking-sessions/1") && method === "GET") {
        return jsonResponse({ ...endedSession, paymentStatus: "PENDING" });
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "GET") {
        getPaymentCalls += 1;
        return jsonResponse({ ...paymentBase, status: "PENDING" });
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderSessionDetailPage();
    await flushMicrotasks();

    expect(screen.getByText("Payment pending…")).toBeInTheDocument();
    expect(getPaymentCalls).toBe(1);

    async function advance(ms: number) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
      });
    }

    // Gap #1-3: 2s each. `flushMicrotasks` itself nudges the fake clock by a few ms, so checkpoints
    // sit safely on either side of each boundary rather than exactly on it.
    await advance(1_900);
    expect(getPaymentCalls).toBe(1);

    await advance(300); // crosses the first 2s gap
    expect(getPaymentCalls).toBe(2);

    await advance(2_000); // crosses the second 2s gap
    expect(getPaymentCalls).toBe(3);

    await advance(2_000); // crosses the third 2s gap
    expect(getPaymentCalls).toBe(4);

    // Gap #4-6: 5s each.
    await advance(5_000);
    expect(getPaymentCalls).toBe(5);

    await advance(5_000);
    expect(getPaymentCalls).toBe(6);

    await advance(5_000);
    expect(getPaymentCalls).toBe(7);

    // Gap #7+: 10s each, until the ~2-minute ceiling is reached (last poll lands around t≈111s).
    await advance(10_000);
    expect(getPaymentCalls).toBe(8);

    await advance(10_000 * 8);
    expect(getPaymentCalls).toBe(16);
    expect(screen.getByText(/still processing.*check history/i)).toBeInTheDocument();

    // The ceiling has been reached: no further request is scheduled, however long the clock runs.
    await advance(60_000);
    expect(getPaymentCalls).toBe(16);
  });

  it("a status read that keeps failing still counts toward the same ~2-minute ceiling and stops there, never appearing to have already given up", async () => {
    vi.useFakeTimers();

    let getPaymentCalls = 0;
    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/parking-sessions/1") && method === "GET") {
        return jsonResponse({ ...endedSession, paymentStatus: "PENDING" });
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "GET") {
        getPaymentCalls += 1;
        if (getPaymentCalls === 1) {
          return jsonResponse({ ...paymentBase, status: "PENDING" });
        }
        // Every read after the first fails outright (settlement service unavailable), yet the poll
        // must keep advancing the same clock a run of successful PENDING reads would.
        return jsonResponse({ code: "error.unknown", message: "Settlement service unavailable." }, 500);
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderSessionDetailPage();
    await flushMicrotasks();

    expect(screen.getByText("Payment pending…")).toBeInTheDocument();
    expect(getPaymentCalls).toBe(1);

    async function advance(ms: number) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
      });
    }

    // A failing read must not make the screen look like watching already stopped.
    await advance(2_300);
    expect(getPaymentCalls).toBe(2);
    expect(screen.getByText("Payment pending…")).toBeInTheDocument();
    expect(screen.queryByText(/could not check the payment status/i)).not.toBeInTheDocument();

    await advance(2_000);
    expect(getPaymentCalls).toBe(3);

    await advance(2_000);
    expect(getPaymentCalls).toBe(4);

    await advance(5_000);
    expect(getPaymentCalls).toBe(5);

    await advance(5_000);
    expect(getPaymentCalls).toBe(6);

    await advance(5_000);
    expect(getPaymentCalls).toBe(7);

    await advance(10_000);
    expect(getPaymentCalls).toBe(8);

    await advance(10_000 * 8);
    expect(getPaymentCalls).toBe(16);
    expect(screen.getByText(/still processing.*check history/i)).toBeInTheDocument();

    // The ceiling has been reached: no further request is scheduled, however long the clock runs —
    // a run of failures never bypasses the bound the successful-PENDING case is held to.
    await advance(60_000);
    expect(getPaymentCalls).toBe(16);
  });

  it("a mocked FAILED status offers Retry, and tapping it posts the payment endpoint again and returns to the pending state", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/parking-sessions/1") && method === "GET") {
        return jsonResponse({ ...endedSession, paymentStatus: "FAILED" });
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "GET") {
        return jsonResponse({ ...paymentBase, status: "FAILED" });
      }
      if (url.endsWith("/parking-sessions/1/payment") && method === "POST") {
        return jsonResponse({ ...paymentBase, status: "PENDING" }, 201);
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderSessionDetailPage();

    expect(await screen.findByText(/payment failed/i)).toBeInTheDocument();

    await user.click(await screen.findByRole("button", { name: /^retry$/i }));

    expect(await screen.findByText("Payment pending…")).toBeInTheDocument();
  });
});