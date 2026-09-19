import { Outlet } from "react-router";
import "./AppShell.css";

/** Page frame shared by every routed screen: product header plus the routed content area. */
function AppShell() {
  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <h1>Parking Management System</h1>
      </header>
      <main className="app-shell__main">
        <Outlet />
      </main>
    </div>
  );
}

export default AppShell;
