import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { queryClient } from "./queryClient";
import { setSessionExpiredHandler } from "../api";
import { setTokens } from "../api/tokenStorage";
import App from "./App";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("App", () => {
  beforeEach(() => {
    setTokens({ accessToken: "access-token", refreshToken: "refresh-token" });
  });

  afterEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
    setSessionExpiredHandler(null);
    queryClient.clear();
  });

  it("renders the shell header and redirects / to the active-parking home screen", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith("/parking-sessions/active")) {
        return jsonResponse([]);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(
      screen.getByRole("heading", { name: "Parking Management System" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: /active parking/i }),
    ).toBeInTheDocument();
    expect(await screen.findByText(/nothing parked right now/i)).toBeInTheDocument();
  });
});
