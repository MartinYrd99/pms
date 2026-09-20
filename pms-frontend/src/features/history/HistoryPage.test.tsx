import { cleanup, render, screen, waitFor } from "@testing-library/react";
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

function renderHistoryPage() {
  const testRouter = createMemoryRouter(routes, { initialEntries: ["/history"] });
  return render(<App router={testRouter} />);
}

const sessionOne = {
  id: 1,
  vehicle: { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
  zone: { id: 10, name: "Blue Zone", city: "Sofia" },
  startedAt: "2026-09-20T08:00:00Z",
  endedAt: "2026-09-20T10:00:00Z",
  amount: 4,
  paymentStatus: "COMPLETED",
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  setSessionExpiredHandler(null);
  queryClient.clear();
});

describe("HistoryPage", () => {
  it("renders a mocked first page's rows with vehicle, zone, amount, payment status and the Sofia-local start time", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.includes("/parking-sessions?") && url.includes("page=0")) {
        return jsonResponse({ content: [sessionOne], page: 0, size: 20, totalElements: 1 });
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderHistoryPage();

    expect(await screen.findByText("CA1111XX — VW Golf")).toBeInTheDocument();
    expect(screen.getByText("Blue Zone, Sofia")).toBeInTheDocument();
    expect(screen.getByText("4.00 EUR")).toBeInTheDocument();
    expect(screen.getByText("Paid")).toBeInTheDocument();
    // 2026-09-20T08:00:00Z is 11:00:00 local time in Europe/Sofia (UTC+3, daylight saving in September).
    expect(screen.getByText("20/09/2026, 11:00:00")).toBeInTheDocument();
  });

  it("tapping Next requests page=1 and renders that page; Previous is disabled on page 0", async () => {
    const user = userEvent.setup();

    const pageZero = {
      content: [sessionOne],
      page: 0,
      size: 20,
      totalElements: 21,
    };
    const pageOne = {
      content: [{ ...sessionOne, id: 2, vehicle: { id: 2, plate: "CA2222XX", brand: "Toyota", model: "Corolla" } }],
      page: 1,
      size: 20,
      totalElements: 21,
    };

    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.includes("/parking-sessions?") && url.includes("page=0")) {
        return jsonResponse(pageZero);
      }
      if (url.includes("/parking-sessions?") && url.includes("page=1")) {
        return jsonResponse(pageOne);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderHistoryPage();

    expect(await screen.findByText("CA1111XX — VW Golf")).toBeInTheDocument();
    const previousButton = screen.getByRole("button", { name: /previous/i });
    expect(previousButton).toBeDisabled();

    await user.click(screen.getByRole("button", { name: /next/i }));

    expect(await screen.findByText("CA2222XX — Toyota Corolla")).toBeInTheDocument();
    expect(screen.queryByText("CA1111XX — VW Golf")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: /previous/i })).toBeEnabled());
    // A single row on a 21-element, size-20 second page is exactly the last page.
    expect(screen.getByRole("button", { name: /next/i })).toBeDisabled();
  });

  it("renders the empty state for a mocked empty first page", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.includes("/parking-sessions?") && url.includes("page=0")) {
        return jsonResponse({ content: [], page: 0, size: 20, totalElements: 0 });
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderHistoryPage();

    expect(await screen.findByText(/no parkings yet/i)).toBeInTheDocument();
  });

  it("renders a readable error rather than a blank screen for a mocked error response", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.includes("/parking-sessions?") && url.includes("page=0")) {
        return jsonResponse({ code: "error.unknown", message: "Boom" }, 500);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderHistoryPage();

    // TanStack Query's default retries delay this past the default findBy timeout.
    expect(
      await screen.findByRole("alert", {}, { timeout: 10_000 }),
    ).toHaveTextContent(/could not load your parking history/i);
  }, 15_000);
});