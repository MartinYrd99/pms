import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import type { FormEvent } from "react";
import { createVehicle, listVehicles } from "../../api";
import { mapVehicleFieldErrors } from "./vehicleErrors";
import "./VehiclesPage.css";

const VEHICLES_QUERY_KEY = ["vehicles"];

/** Lists the signed-in driver's own vehicles and lets them register another by plate, brand and model. */
function VehiclesPage() {
  const plateId = useId();
  const brandId = useId();
  const modelId = useId();

  const [plate, setPlate] = useState("");
  const [brand, setBrand] = useState("");
  const [model, setModel] = useState("");

  const vehiclesQuery = useQuery({
    queryKey: VEHICLES_QUERY_KEY,
    queryFn: listVehicles,
  });

  const queryClient = useQueryClient();
  const createVehicleMutation = useMutation({
    mutationFn: () => createVehicle({ plate, brand, model }),
    onSuccess: () => {
      setPlate("");
      setBrand("");
      setModel("");
      void queryClient.invalidateQueries({ queryKey: VEHICLES_QUERY_KEY });
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    createVehicleMutation.mutate();
  }

  const fieldErrors = createVehicleMutation.isError
    ? mapVehicleFieldErrors(createVehicleMutation.error)
    : {};

  return (
    <section className="vehicles-page">
      <h2>Your vehicles</h2>
      {vehiclesQuery.isLoading && <p className="vehicles-page__status">Loading vehicles…</p>}
      {vehiclesQuery.isError && (
        <p role="alert" className="vehicles-page__status vehicles-page__error">
          Could not load your vehicles. Please try again.
        </p>
      )}
      {vehiclesQuery.isSuccess && vehiclesQuery.data.length === 0 && (
        <p className="vehicles-page__status">No vehicles yet, add your first one.</p>
      )}
      {vehiclesQuery.isSuccess && vehiclesQuery.data.length > 0 && (
        <table className="vehicles-page__table">
          <thead>
            <tr>
              <th scope="col">Plate</th>
              <th scope="col">Brand</th>
              <th scope="col">Model</th>
            </tr>
          </thead>
          <tbody>
            {vehiclesQuery.data.map((vehicle) => (
              <tr key={vehicle.id}>
                <td>{vehicle.plate}</td>
                <td>{vehicle.brand}</td>
                <td>{vehicle.model}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h3>Add a vehicle</h3>
      <form className="vehicle-form" onSubmit={handleSubmit} noValidate>
        <div className="vehicle-form__field">
          <label htmlFor={plateId}>Plate</label>
          <input
            id={plateId}
            name="plate"
            type="text"
            required
            value={plate}
            onChange={(event) => setPlate(event.target.value)}
          />
          {fieldErrors.plate && (
            <p role="alert" className="vehicle-form__field-error">
              {fieldErrors.plate}
            </p>
          )}
        </div>
        <div className="vehicle-form__field">
          <label htmlFor={brandId}>Brand</label>
          <input
            id={brandId}
            name="brand"
            type="text"
            required
            value={brand}
            onChange={(event) => setBrand(event.target.value)}
          />
          {fieldErrors.brand && (
            <p role="alert" className="vehicle-form__field-error">
              {fieldErrors.brand}
            </p>
          )}
        </div>
        <div className="vehicle-form__field">
          <label htmlFor={modelId}>Model</label>
          <input
            id={modelId}
            name="model"
            type="text"
            required
            value={model}
            onChange={(event) => setModel(event.target.value)}
          />
          {fieldErrors.model && (
            <p role="alert" className="vehicle-form__field-error">
              {fieldErrors.model}
            </p>
          )}
        </div>
        {fieldErrors.general && (
          <p role="alert" className="vehicle-form__error">
            {fieldErrors.general}
          </p>
        )}
        <button type="submit" disabled={createVehicleMutation.isPending}>
          {createVehicleMutation.isPending ? "Adding…" : "Add vehicle"}
        </button>
      </form>
    </section>
  );
}

export default VehiclesPage;