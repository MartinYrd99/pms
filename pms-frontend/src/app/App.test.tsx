import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { queryClient } from "./queryClient";
import { setSessionExpiredHandler } from "../api";
import { stubAuthenticatedFetch } from "../test/apiFetchMock";
import App from "./App";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("App", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setSessionExpiredHandler(null);
    queryClient.clear();
  });

  it("renders the shell header and redirects / to the active-parking home screen", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.endsWith("/parking-sessions/active")) {
        return jsonResponse([]);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    render(<App />);

    expect(
      await screen.findByRole("heading", { name: "Parking Management System" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { name: /active parking/i }),
    ).toBeInTheDocument();
    expect(await screen.findByText(/nothing parked right now/i)).toBeInTheDocument();
  });
});
