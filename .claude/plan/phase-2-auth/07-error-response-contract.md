---
exec-order: 7
category: add
depends-on: [6]
status: done
suggested-agents: [backend-developer]
---

# Establish the single error response contract and its exception types

## Business description

Every failure the API can produce must look the same to the client: one small JSON body, one status
code chosen by one rule. Without this, each later endpoint invents its own error shape, the SPA ends
up with special-case handling per screen, and stack traces or SQL text leak to users.

The contract this ticket lands:

- **One body everywhere:** `{"code": "...", "message": "..."}` — nothing else. `code` is a stable
  machine-readable token the client can branch on; `message` is human-readable text resolved from the
  message bundle.
- **Status mapping, exactly:**
  - `400` — request validation failed (missing field, malformed body, bad type)
  - `401` — not authenticated (no token, invalid or expired token)
  - `403` — authenticated but not the owner of the resource
  - `404` — the resource does not exist
  - `409` — a business rule refused the request
- **Business rules are 409s, never 400s.** The request was well-formed; the *state* refused it
  ("vehicle already parked", "session already ended", "zone inactive", "username taken"). Only
  malformed input is a 400.
- **Errors never leak internals** (design N4): no stack traces, no exception class names, no SQL, no
  constraint or table names in the response. Anything unmapped becomes a generic `500` with a fixed
  code and a neutral message, logged in full server-side.

**What to build:** a global `@RestControllerAdvice` handling the mapping above, the error response
record, a `messages.properties` bundle keyed with `validation.*` keys, and the exception types the
later tickets throw — following the project's convention: `IllegalArgumentException` → 400,
`IllegalStateException` → 409, `EntityNotFoundException` → 404, plus an ownership/access-denied
exception → 403, and Spring Security's authentication failure → 401. Bean Validation failures
(`MethodArgumentNotValidException`, constraint violations) map to 400 with field detail in `message`.

Some 409 bodies later need to carry a payload (the blocking session id on a double start, the ended
session on a double end). Design the advice so a richer body can be added in that phase **without
changing the base `{code, message}` shape** — but do not add those payloads now.

Out of scope: any endpoint, any security filter chain, any domain exception whose feature
does not exist yet.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Slice test with a throwaway test controller: each of `IllegalArgumentException`,
  `IllegalStateException`, `EntityNotFoundException` and the access-denied exception produces its
  mapped status (400 / 409 / 404 / 403) and a body containing exactly `code` and `message`.
- Test that a Bean Validation failure on a request body returns 400 in the same body shape.
- Test that an unmapped `RuntimeException` returns 500 with a generic code and a message containing
  no exception class name, stack trace or SQL fragment.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: none — this ticket carries no concurrency invariant.
