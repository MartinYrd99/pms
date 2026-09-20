import { createBrowserRouter, Navigate } from "react-router";
import type { RouteObject } from "react-router";
import ActivePage from "../features/active/ActivePage";
import LoginPage from "../features/auth/LoginPage";
import RegisterPage from "../features/auth/RegisterPage";
import HistoryPage from "../features/history/HistoryPage";
import ParkPage from "../features/parking/ParkPage";
import SessionDetailPage from "../features/sessions/SessionDetailPage";
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
          // /active is the signed-in driver's home destination; "/" just redirects there.
          { index: true, element: <Navigate to="/active" replace /> },
          { path: "active", element: <ActivePage /> },
          { path: "vehicles", element: <VehiclesPage /> },
          { path: "park", element: <ParkPage /> },
          { path: "history", element: <HistoryPage /> },
          { path: "sessions/:id", element: <SessionDetailPage /> },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);