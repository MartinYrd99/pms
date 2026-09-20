import { vi } from "vitest";

type FetchHandler = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response> | Response;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/**
 * Every screen behind the route guard boots through AuthProvider's restoreSession(), which fires
 * a `POST /auth/refresh` before anything else renders. Tests that only care about a screen's own
 * endpoints stub that boot call to a fixed access token so it doesn't need repeating everywhere.
 */
export function stubAuthenticatedFetch(handler: FetchHandler, accessToken = "access-1") {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);

    if (url.endsWith("/auth/refresh")) {
      return jsonResponse({ accessToken });
    }

    return handler(input, init);
  });

  vi.stubGlobal("fetch", fetchMock);

  return fetchMock;
}