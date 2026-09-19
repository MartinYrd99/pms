import { QueryClientProvider } from "@tanstack/react-query";
import type { ComponentProps } from "react";
import { RouterProvider } from "react-router";
import { queryClient } from "./queryClient";
import { router } from "./router";

type AppProps = {
  // Defaults to the real app router; overridable so a test can exercise the real
  // QueryClientProvider wiring with a test-local route instead of the browser router.
  router?: ComponentProps<typeof RouterProvider>["router"];
};

function App({ router: routerProp = router }: AppProps = {}) {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={routerProp} />
    </QueryClientProvider>
  );
}

export default App;
