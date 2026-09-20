import { render, screen } from "@testing-library/react";
import { createMemoryRouter } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import App from "../../app/App";
import { routes } from "../../app/router";

beforeEach(() => {
  localStorage.clear();
});

describe("AuthGuard", () => {
  it("renders the login screen instead of a guarded route when there is no stored token", () => {
    const testRouter = createMemoryRouter(routes, { initialEntries: ["/"] });
    render(<App router={testRouter} />);

    expect(screen.getByRole("heading", { name: /sign in/i })).toBeInTheDocument();
    expect(screen.queryByText(/parking screens will appear here/i)).not.toBeInTheDocument();
  });
});