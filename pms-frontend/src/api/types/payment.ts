import type { PaymentStatus } from "./parkingSession";

export interface PaymentResponse {
  id: number;
  sessionId: number;
  amount: number;
  status: PaymentStatus;
  createdAt: string;
  settledAt: string | null;
}