import { Link, Outlet, useNavigate } from "react-router";
import { logout } from "../../api";
import { useAuth } from "../auth/AuthContext";
import "./AppShell.css";

/** Page frame shared by every routed screen: product header plus the routed content area. */
function AppShell() {
  const { status, signOut } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    try {
      // Revokes the refresh token server-side; tokenStorage clears locally even if this throws.
      await logout();
    } finally {
      signOut();

      navigate("/login", { replace: true });
    }
  }

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <h1>Parking Management System</h1>
        {status === "authenticated" && (
          <button
            type="button"
            className="app-shell__logout"
            onClick={() => {
              void handleLogout();
            }}
          >
            Log out
          </button>
        )}
      </header>
      {status === "authenticated" && (
        <nav className="app-shell__nav">
          <Link to="/active">Active parking</Link>
          <Link to="/park">Start parking</Link>
          <Link to="/vehicles">Vehicles</Link>
        </nav>
      )}
      <main className="app-shell__main">
        <Outlet />
      </main>
    </div>
  );
}

export default AppShell;
