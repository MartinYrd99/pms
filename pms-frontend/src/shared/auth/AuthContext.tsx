import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { restoreSession, setSessionExpiredHandler } from "../../api";

export type AuthStatus = "checking" | "authenticated" | "unauthenticated";

interface AuthContextValue {
  status: AuthStatus;
  /** Flips the shared state to signed-in once the API client has stored a fresh access token. */
  signIn: () => void;
  /** Flips the shared state to signed-out; the caller is responsible for clearing tokens. */
  signOut: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

interface AuthProviderProps {
  children: ReactNode;
}

/** Single source of truth for "is anyone signed in", read by the shell header and the route guard. */
export function AuthProvider({ children }: AuthProviderProps) {
  const [status, setStatus] = useState<AuthStatus>("checking");

  const signIn = useCallback(() => setStatus("authenticated"), []);
  const signOut = useCallback(() => setStatus("unauthenticated"), []);

  // The access token never survives a reload, so booting the app means asking the HttpOnly
  // refresh cookie for a fresh one exactly once before the route guard can decide anything.
  useEffect(() => {
    let cancelled = false;

    restoreSession()
      .then((restored) => {
        if (!cancelled) {
          setStatus(restored ? "authenticated" : "unauthenticated");
        }
      })
      .catch(() => {
        if (!cancelled) {
          setStatus("unauthenticated");
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  // Subscribes to the API client's callback so a refresh token that dies while the app is open
  // signs the whole app out, not just the screen that happened to make the failing call.
  useEffect(() => {
    setSessionExpiredHandler(signOut);

    return () => setSessionExpiredHandler(null);
  }, [signOut]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, signIn, signOut }),
    [status, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);

  if (context === null) {
    throw new Error("useAuth must be used within an AuthProvider");
  }

  return context;
}