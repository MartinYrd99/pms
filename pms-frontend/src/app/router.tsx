import { createBrowserRouter } from "react-router";
import type { RouteObject } from "react-router";
import LoginPage from "../features/auth/LoginPage";
import RegisterPage from "../features/auth/RegisterPage";
import HomePage from "../features/home/HomePage";
import VehiclesPage from "../features/vehicles/VehiclesPage";
import AppShell from "../shared/layout/AppShell";
import AuthGuard from "../shared/auth/AuthGuard";

export const routes: RouteObject[] = [
  { path: "/login", element: <LoginPage /> },
  { path: "/register", element: <RegisterPage /> },
  {
    path: "/",
    element: <AuthGuard />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <HomePage /> },
          { path: "vehicles", element: <VehiclesPage /> },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);