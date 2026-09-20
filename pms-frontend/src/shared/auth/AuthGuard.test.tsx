import { render, screen } from "@testing-library/react";
import { createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { routes } from "../../app/router";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("AuthGuard", () => {
  it("renders the login screen instead of a guarded route when there is no refresh cookie", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ code: "auth.invalid_refresh_token", message: "Unauthorized" }, 401);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const testRouter = createMemoryRouter(routes, { initialEntries: ["/"] });
    render(<App router={testRouter} />);

    expect(await screen.findByRole("heading", { name: /sign in/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /active parking/i })).not.toBeInTheDocument();
  });
});
