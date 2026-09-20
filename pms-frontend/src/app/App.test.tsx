import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setTokens } from "../api/tokenStorage";
import App from "./App";

describe("App", () => {
  beforeEach(() => {
    setTokens({ accessToken: "access-token", refreshToken: "refresh-token" });
  });

  afterEach(() => {
    localStorage.clear();
  });

  it("renders the shell header and the placeholder screen at /", () => {
    render(<App />);

    expect(
      screen.getByRole("heading", { name: "Parking Management System" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/parking screens will appear here/i),
    ).toBeInTheDocument();
  });
});
