import { createBrowserRouter } from "react-router";
import AppShell from "../shared/layout/AppShell";
import HomePage from "../features/home/HomePage";

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [{ index: true, element: <HomePage /> }],
  },
]);