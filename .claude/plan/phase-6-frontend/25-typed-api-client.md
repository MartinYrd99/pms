---
exec-order: 25
category: add
depends-on: [24]
status: done
suggested-agents: [frontend-developer]
---

# Build the typed API client with token storage and the 401 → refresh → retry interceptor

## Business description

Every screen in this app talks to the same fourteen endpoints, and every one of them can be hit at
the moment the 10-minute access token expires. If each screen solved that itself, a user would be
thrown back to the login form mid-parking. This ticket delivers the single place all of that lives:
one typed client under `pms-frontend/src/api/` that owns the request shapes, the bearer token, the
error shape and the token renewal. From here on a component never touches `fetch`, tokens or refresh
logic.

**The fourteen endpoints, all relative to `/api/v1`, all JSON.** Public: `POST /auth/register
{username,password}` → 201; `POST /auth/login {username,password}` → `{accessToken, refreshToken}`;
`POST /auth/refresh {refreshToken}` → a new `{accessToken, refreshToken}` (the old one is revoked —
rotation), 401 if unknown/expired/already used. Then `POST /auth/logout {refreshToken}` → 204;
`GET /vehicles`; `POST /vehicles {plate, brand, model}` → 201, 409 on duplicate plate;
`GET /zones` → active zones each carrying `hourlyRate`, `currency`, `ruleType`;
`POST /parking-sessions {vehicleId, zoneId}` → 201, 403/404/409; `GET /parking-sessions/active`;
`POST /parking-sessions/{id}/end` → 200 with `amount`, 409 if already ended;
`GET /parking-sessions/{id}`; `POST /parking-sessions/{id}/payment` → 201 `{status:"PENDING"}` or
200 with the existing payment (idempotent), 409 if the session is still active;
`GET /parking-sessions/{id}/payment` → the payment, 404 if never paid;
`GET /parking-sessions?page=&size=` → the paginated history. **No user id ever appears in a URL or
body** — the user is always the authenticated principal. Type every request and response explicitly;
take the exact field names and the history paging envelope from the running backend's
`/swagger-ui.html` (or its OpenAPI JSON) and mirror them — do not invent fields the backend does not
return, and do not use `any`.

**Errors are typed, because screens must branch on them.** The backend always answers a failure with
`{code, message}`. Every non-2xx becomes one thrown `ApiError` carrying the HTTP `status`, the
`code`, the `message` and the **parsed response body** — the body matters because a 409 from start
carries the blocking session and a 409 from end carries the ended session with its amount. A type
guard lets a screen write "was this a 409, and what session came back with it?" without parsing
anything itself.

**Token renewal happens once, invisibly.** Tokens are stored by this module only (browser
`localStorage` under one key, so a 48-hour refresh token survives a reload or an app relaunch), and
the access token is attached as `Authorization: Bearer <token>` to every call except register, login
and refresh. When any request comes back **401**, the client calls `POST /auth/refresh` **exactly
once**, stores the rotated pair and **retries the original request once** — never a loop, never a
second refresh for the same request. If the refresh itself fails, or the retry is 401 again, the
client clears the stored tokens and fires a single "session expired" callback that ticket 26 wires
to the login screen. Because refresh tokens rotate, **concurrent 401s must share one in-flight
refresh call** — two parallel requests may never each burn a refresh token.

**Out of scope:** any React component, hook, route or screen; this ticket ships the module and its
tests only.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket (mocked `fetch`):

- A `GET /vehicles` answering 401 triggers exactly one `POST /auth/refresh`, and the single retry
  carries the new access token and returns the retried response.
- Two requests that both receive 401 at the same time cause **one** refresh call, and both are
  retried and resolve.
- A failed refresh clears the stored tokens and fires the "session expired" callback once.
- A 409 from `POST /parking-sessions` surfaces as a typed error whose `status`, `code`, `message` and
  parsed body (the blocking session id) are all readable by the caller.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing screen exists yet; the client is exercised end to end from ticket 26 on.
- Stress: none — the shared-refresh race is covered by the concurrent-401 test above.
