import { isApiError } from "../../api";

export interface VehicleFieldErrors {
  plate?: string;
  brand?: string;
  model?: string;
  general?: string;
}

const VEHICLE_FIELDS = ["plate", "brand", "model"] as const;

/**
 * Turns a caught create-vehicle error into field-level messages. A 409 is always the plate
 * uniqueness conflict (the only 409 this endpoint returns), so it lands on the plate field. A 400
 * carries Bean Validation detail with the offending field name in the message text, so it is
 * matched back onto that field; anything else falls back to a general message.
 */
export function mapVehicleFieldErrors(error: unknown): VehicleFieldErrors {
  if (!isApiError(error)) {
    return { general: "Something went wrong. Please try again." };
  }

  if (error.status === 409) {
    return { plate: error.message };
  }

  if (error.status === 400) {
    const lowerMessage = error.message.toLowerCase();
    const fieldErrors: VehicleFieldErrors = {};

    for (const field of VEHICLE_FIELDS) {
      if (lowerMessage.includes(field)) {
        fieldErrors[field] = error.message;
      }
    }

    return Object.keys(fieldErrors).length > 0 ? fieldErrors : { general: error.message };
  }

  return { general: error.message };
}