import { Navigate, Outlet } from "react-router";
import { useAuth } from "./AuthContext";

/** Closes every route beneath it to a signed-in session; anyone else is sent to /login, never a blank or broken screen. */
function AuthGuard() {
  const { status } = useAuth();

  if (status === "checking") {
    return null;
  }

  if (status === "unauthenticated") {
    return <Navigate to="/login" replace />;
  }

  return <Outlet />;
}

export default AuthGuard;