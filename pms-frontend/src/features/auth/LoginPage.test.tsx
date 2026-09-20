import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../../app/App";
import { queryClient } from "../../app/queryClient";
import { routes } from "../../app/router";
import { setSessionExpiredHandler } from "../../api";
import { getAccessToken } from "../../api/tokenStorage";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  setSessionExpiredHandler(null);
  queryClient.clear();
});

describe("LoginPage", () => {
  it("stores the token pair through the API client and lands on the active-parking home screen on success", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ code: "auth.invalid_refresh_token", message: "Unauthorized" }, 401);
      }
      if (url.endsWith("/auth/login")) {
        return jsonResponse({ accessToken: "access-1" });
      }
      if (url.endsWith("/parking-sessions/active")) {
        return jsonResponse([]);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const testRouter = createMemoryRouter(routes, { initialEntries: ["/login"] });
    render(<App router={testRouter} />);

    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "secret123");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(
      await screen.findByRole("heading", { name: /active parking/i }),
    ).toBeInTheDocument();
    expect(getAccessToken()).toBe("access-1");
  });

  it("renders 'invalid username or password' on a 401 and keeps the user on /login with the username filled in", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ code: "auth.invalid_refresh_token", message: "Unauthorized" }, 401);
      }
      if (url.endsWith("/auth/login")) {
        return jsonResponse({ code: "auth.invalid_credentials", message: "Bad credentials" }, 401);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const testRouter = createMemoryRouter(routes, { initialEntries: ["/login"] });
    render(<App router={testRouter} />);

    const usernameInput = screen.getByLabelText(/username/i);
    await user.type(usernameInput, "bob");
    await user.type(screen.getByLabelText(/password/i), "wrong-pass");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText(/invalid username or password/i)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /sign in/i })).toBeInTheDocument();
    expect(usernameInput).toHaveValue("bob");
    expect(getAccessToken()).toBeNull();
  });
});