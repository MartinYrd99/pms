import { QueryClient } from "@tanstack/react-query";

/** Single QueryClient instance for the app — every screen's server state flows through this. */
export const queryClient = new QueryClient();
