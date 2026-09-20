import { apiRequest } from "./client";
import type { PaymentResponse } from "./types/payment";

export async function payForParkingSession(sessionId: number): Promise<PaymentResponse> {
  return apiRequest<PaymentResponse>({
    method: "POST",
    path: `/parking-sessions/${sessionId}/payment`,
  });
}

export async function getParkingSessionPayment(sessionId: number): Promise<PaymentResponse> {
  return apiRequest<PaymentResponse>({
    method: "GET",
    path: `/parking-sessions/${sessionId}/payment`,
  });
}