---
exec-order: 26
category: add
depends-on: [25]
status: done
suggested-agents: [frontend-developer]
---

# Deliver the register and login screens, logout and the protected route guard

## Business description

This is the front door of the app. A new driver creates an account, an existing one signs in, and
everything else in the product is closed to anyone who has not. Until this ticket the app has a
placeholder route; after it the app has a real identity and every later screen can assume there is a
signed-in user.

**Register** (`/register`, public): a form with username and password. Submitting calls
`POST /api/v1/auth/register {username, password}` → **201**; the user is then sent to the login
screen with a short "account created — sign in" confirmation. Any 4xx renders the backend's
`{code, message}` as a readable message on the form, not a raw error object.

**Login** (`/login`, public): username and password → `POST /api/v1/auth/login` →
`{accessToken, refreshToken}`, which are handed to the API client from ticket 25 to store. On
success the user lands on the app's **home screen** at `/`. A **401** renders "invalid username or
password" and keeps the user on the form with the username still filled in.

**Logout**: an action in the app shell header, visible only when signed in. It calls
`POST /api/v1/auth/logout {refreshToken}` → 204 and then clears the stored tokens and returns the
user to `/login`. The tokens are cleared locally **even if the call fails** — a user who taps logout
is logged out of this device regardless of the network.

**The guard**: every route except `/register` and `/login` requires a stored token. Without one the
user is redirected to `/login` instead of seeing a screen flash or a blank page. The guard also
listens to the "session expired" callback the API client fires when a refresh fails (ticket 25), so
a token that dies while the app is open ends on the login screen rather than on a broken screen. The
signed-in state is shared through one small auth context — no global state library.

**Out of scope:** password strength rules, "remember me", password reset, profile editing, and the
content of the home screen beyond what the shell already renders (vehicles, parking and history
arrive in tickets 27–31).

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket (mocked API):

- Submitting the login form with a mocked success stores the token pair through the API client and
  renders the home screen.
- A mocked 401 from login renders "invalid username or password" and the user stays on `/login`.
- Rendering a guarded route with no stored token renders the login screen instead.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser against the running Compose stack — register a brand-new username, get sent to
  login, sign in, land on home; reload the page and stay signed in; type a guarded URL directly in
  the address bar while signed out → land on login; sign in again, tap logout → back on login, and
  the guarded URL bounces to login again.
- Stress: none — this ticket carries no concurrency invariant.
