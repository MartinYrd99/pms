import { cleanup, render, screen } from "@testing-library/react";
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

function renderVehiclesPage() {
  const testRouter = createMemoryRouter(routes, { initialEntries: ["/vehicles"] });
  return render(<App router={testRouter} />);
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  setSessionExpiredHandler(null);
  queryClient.clear();
});

describe("VehiclesPage", () => {
  it("renders both plates for a mocked two-vehicle response", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.endsWith("/vehicles")) {
        return jsonResponse([
          { id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" },
          { id: 2, plate: "CA2222XX", brand: "Toyota", model: "Corolla" },
        ]);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderVehiclesPage();

    expect(await screen.findByText("CA1111XX")).toBeInTheDocument();
    expect(screen.getByText("CA2222XX")).toBeInTheDocument();
  });

  it("renders the empty state for a mocked empty response", async () => {
    stubAuthenticatedFetch(async (input) => {
      const url = String(input);
      if (url.endsWith("/vehicles")) {
        return jsonResponse([]);
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    renderVehiclesPage();

    expect(await screen.findByText(/no vehicles yet, add your first one/i)).toBeInTheDocument();
  });

  it("posts the entered plate/brand/model and shows the new vehicle in the refreshed list", async () => {
    const user = userEvent.setup();
    let vehicles = [{ id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" }];

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/vehicles") && method === "GET") {
        return jsonResponse(vehicles);
      }
      if (url.endsWith("/vehicles") && method === "POST") {
        const body = JSON.parse(String(init?.body)) as { plate: string; brand: string; model: string };
        const created = { id: 2, ...body };
        vehicles = [...vehicles, created];
        return jsonResponse(created, 201);
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderVehiclesPage();

    expect(await screen.findByText("CA1111XX")).toBeInTheDocument();

    await user.type(screen.getByLabelText(/plate/i), "CA3333XX");
    await user.type(screen.getByLabelText(/brand/i), "Honda");
    await user.type(screen.getByLabelText(/model/i), "Civic");
    await user.click(screen.getByRole("button", { name: /add vehicle/i }));

    expect(await screen.findByText("CA3333XX")).toBeInTheDocument();
    expect(screen.getByText("Honda")).toBeInTheDocument();
    expect(screen.getByText("Civic")).toBeInTheDocument();
    expect(screen.getByLabelText(/plate/i)).toHaveValue("");
    expect(screen.getByLabelText(/brand/i)).toHaveValue("");
    expect(screen.getByLabelText(/model/i)).toHaveValue("");
  });

  it("renders the duplicate-plate message on a 409, keeps the typed values and leaves the list unchanged", async () => {
    const user = userEvent.setup();

    stubAuthenticatedFetch(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url.endsWith("/vehicles") && method === "GET") {
        return jsonResponse([{ id: 1, plate: "CA1111XX", brand: "VW", model: "Golf" }]);
      }
      if (url.endsWith("/vehicles") && method === "POST") {
        return jsonResponse(
          { code: "validation.vehicle.plate-taken", message: "This plate is already registered." },
          409,
        );
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });

    renderVehiclesPage();

    expect(await screen.findByText("CA1111XX")).toBeInTheDocument();

    const plateInput = screen.getByLabelText(/plate/i);
    const brandInput = screen.getByLabelText(/brand/i);
    const modelInput = screen.getByLabelText(/model/i);

    await user.type(plateInput, "CA1111XX");
    await user.type(brandInput, "VW");
    await user.type(modelInput, "Polo");
    await user.click(screen.getByRole("button", { name: /add vehicle/i }));

    expect(await screen.findByText(/this plate is already registered/i)).toBeInTheDocument();
    expect(plateInput).toHaveValue("CA1111XX");
    expect(brandInput).toHaveValue("VW");
    expect(modelInput).toHaveValue("Polo");

    // Still exactly one row: the duplicate submission never made it into the list.
    expect(screen.getAllByText("CA1111XX")).toHaveLength(1);
  });
});