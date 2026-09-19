import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import App from "./App";

describe("App", () => {
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
