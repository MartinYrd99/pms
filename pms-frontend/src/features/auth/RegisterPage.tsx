import { useMutation } from "@tanstack/react-query";
import { useId, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { register } from "../../api";
import { extractApiErrorMessage } from "./authErrors";
import "./AuthForm.css";

/** Public sign-up screen; success sends the driver to /login with a confirmation, it does not sign them in. */
function RegisterPage() {
  const navigate = useNavigate();
  const usernameId = useId();
  const passwordId = useId();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  const registerMutation = useMutation({
    mutationFn: () => register({ username, password }),
    onSuccess: () => {
      navigate("/login", { replace: true, state: { justRegistered: true } });
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    registerMutation.mutate();
  }

  return (
    <section className="auth-form">
      <h2>Create your account</h2>
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
            autoComplete="new-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </div>
        {registerMutation.isError && (
          <p role="alert" className="auth-form__error">
            {extractApiErrorMessage(registerMutation.error)}
          </p>
        )}
        <button type="submit" disabled={registerMutation.isPending}>
          {registerMutation.isPending ? "Creating account…" : "Create account"}
        </button>
      </form>
      <p>
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </section>
  );
}

export default RegisterPage;