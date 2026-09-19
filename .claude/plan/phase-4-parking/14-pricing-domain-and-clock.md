---
exec-order: 14
category: add
depends-on: [1, 13]
status: done
suggested-agents: [backend-developer]
---

# Pin the hourly pricing rule and make time injectable

## Business description

The whole product exists to charge people the right amount, and the charging rule is deliberately
blunt: **every started hour is a full hour, with a minimum of one hour.** A 35-minute stay costs one
hour. Exactly 60 minutes costs one hour. 60 minutes and one second costs **two** hours. At 2.00 EUR/h
a 2 h 05 min stay in the Blue Zone is 3 × 2.00 = **6.00 EUR**.

That rule is easy to describe and easy to get wrong, so this ticket makes it one small, pure piece of
code with no database and no HTTP around it — a thing that takes a start instant, an end instant and
a tariff, and returns an amount. Everything else in the system will call it instead of re-deriving
the rule. The same ticket makes "what time is it" an injectable dependency, so tests can place a
parking session at an exact millisecond instead of sleeping.

**Scope**

1. **A `Clock` bean.** Register a single `java.time.Clock` (UTC) in the application configuration and
   have every piece of production code that needs the current instant take it as a dependency —
   never `Instant.now()` / `System.currentTimeMillis()` called statically. Tests replace it with a
   fixed or adjustable clock. No endpoint, no scheduling behaviour here.

2. **A pure pricing component.** Given `startedAt`, `endedAt` and the tariff (its `hourlyRate` and
   `ruleType`), it returns the amount:

   ```
   millis  = endedAt.toEpochMilli() - startedAt.toEpochMilli()
   hours   = max(1, ceilDiv(millis, 3_600_000))          // integer math only
   amount  = BigDecimal.valueOf(hours) * tariff.hourlyRate
   amount  = amount.setScale(2)                           // exact, EUR
   ```

   **Integer math only.** No `double`/`float` anywhere on this path, and **no intermediate rounding to
   minutes** — a 60 min 30 s stay must not first become 60 minutes and then 1 hour; the 61st minute
   has started, so it is 2 hours. Any positive remainder rolls up. The currency is EUR; scale is 2.
   `ruleType` is `HOURLY` (the only value in v1); an unknown rule type is a programming error, not a
   user error — fail fast.

**This ticket has no endpoint and no persistence.** It is the domain piece that ticket 17 (end
parking) will call to compute the stored amount. Nothing here reads or writes `parking_sessions`.

**Out of scope:** the end-parking transaction, storing the amount, any other rule type.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Unit test over the boundary set, which **is** the specification — with a 1.00 EUR/h tariff, a
  duration of `0 ms → 1 h`, `3_600_000 ms → 1 h`, `3_600_001 ms → 2 h`, `7_200_000 ms → 2 h`,
  `7_200_001 ms → 3 h`. Assert the hour count for each.
- Unit test: the 60 min 30 s case (`3_630_000 ms`) prices as **2** hours, guarding against
  minute-granularity rounding.
- Unit test: with the Blue-Zone rate 2.00 EUR/h a 2 h 05 min stay yields exactly `6.00` as a
  `BigDecimal` with scale 2 (assert on `compareTo` **and** on the scale).
- Unit test: a fixed `Clock` bean injected into a collaborator yields the exact instant it was
  configured with, proving time is injectable.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no callable surface; this ticket adds no endpoint.
- Stress: none — the component is pure and stateless, carrying no concurrency invariant.
