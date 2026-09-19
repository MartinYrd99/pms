import { useQuery } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { createMemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { queryClient } from "./queryClient";

type ProbeResponse = { message: string };

// Rendered through the real App composition (its own QueryClientProvider, not a test-only one) so
// this fails if QueryClientProvider is ever removed from the app root.
function Probe() {
  const { data, isLoading, isError } = useQuery<ProbeResponse>({
    queryKey: ["probe"],
    queryFn: async () => {
      const response = await fetch("/api/v1/probe");
      return (await response.json()) as ProbeResponse;
    },
  });

  if (isLoading) {
    return <p>Loading…</p>;
  }
  if (isError || !data) {
    return <p>Error</p>;
  }
  return <p>{data.message}</p>;
}

describe("QueryClientProvider", () => {
  beforeEach(() => {
    const mockResponse = { json: async () => ({ message: "hello from the API" }) } as Response;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(mockResponse));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    queryClient.clear();
  });

  it("resolves a useQuery call against a mocked fetch and renders its data", async () => {
    // A test-local route tree so the probe is reachable without adding a production route,
    // while still rendering through the real App (and thus its real QueryClientProvider).
    const testRouter = createMemoryRouter([{ path: "/", element: <Probe /> }]);
    render(<App router={testRouter} />);

    expect(await screen.findByText("hello from the API")).toBeInTheDocument();
  });
});
