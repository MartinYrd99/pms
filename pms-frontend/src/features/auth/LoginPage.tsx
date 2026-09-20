import { useMutation } from "@tanstack/react-query";
import { useId, useState } from "react";
import type { FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { isApiError, login } from "../../api";
import { useAuth } from "../../shared/auth/AuthContext";
import { extractApiErrorMessage } from "./authErrors";
import "./AuthForm.css";

interface LoginLocationState {
  justRegistered?: boolean;
}

/** Public sign-in screen; a successful login hands the token pair to the API client and opens the app. */
function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const usernameId = useId();
  const passwordId = useId();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  const loginMutation = useMutation({
    mutationFn: () => login({ username, password }),
    onSuccess: () => {
      signIn();
      navigate("/active", { replace: true });
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    loginMutation.mutate();
  }

  const justRegistered = (location.state as LoginLocationState | null)?.justRegistered === true;

  return (
    <section className="auth-form">
      <h2>Sign in</h2>
      {justRegistered && (
        <p role="status" className="auth-form__notice">
          Account created — sign in.
        </p>
      )}
      <form onSubmit={handleSubmit} noValidate>
        <div className="auth-form__field">
          <label htmlFor={usernameId}>Username</label>
          <input
            id={usernameId}
            name="username"
            type="text"
            autoComplete="username"
            required
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
        </div>
        <div className="auth-form__field">
          <label htmlFor={passwordId}>Password</label>
          <input
            id={passwordId}
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </div>
        {loginMutation.isError && (
          <p role="alert" className="auth-form__error">
            {describeLoginError(loginMutation.error)}
          </p>
        )}
        <button type="submit" disabled={loginMutation.isPending}>
          {loginMutation.isPending ? "Signing in…" : "Sign in"}
        </button>
      </form>
      <p>
        No account? <Link to="/register">Register</Link>
      </p>
    </section>
  );
}

function describeLoginError(error: unknown): string {
  if (isApiError(error) && error.status === 401) {
    return "Invalid username or password.";
  }

  return extractApiErrorMessage(error);
}

export default LoginPage;