---
name: task
description: "Run a task by spawning the agents named in its frontmatter, loop with the matching *-code-reviewer until clean, then verify it end to end and under stress against the running stack. Argument is the task description (any length), or @<path> to load the task from a file (e.g. @.claude/plan/9-cascade-stage3-seam-managed.md)."
argument-hint: "[task description | @path/to/task.md]"
allowed-tools: Read, Grep, Glob, Bash, Write, Edit, Agent, Skill
---

# Run Task: $ARGUMENTS

You are running a task end-to-end: read the task, spawn the agents it names, loop with the matching `*-code-reviewer` until the change is clean, then verify it for real — E2E through a browser and under stress — against the running stack.

## Step 0 — Resolve task input

If `$ARGUMENTS` starts with `@` (e.g. `@.claude/plan/9-cascade-stage3-seam-managed.md`):
- Strip the leading `@` to get the file path and `Read` the file.
- Capture its frontmatter — especially **`suggested-agents`** (who to spawn, Step 2) and `id` / `status` (for the final summary).
- The **task description** for the rest of this skill is the full body after the frontmatter (its `## Business description`,). Treat that as `$ARGUMENTS` for all subsequent steps.
- Capture its **`## Testing`** section separately and keep it for the whole run. Its two halves go to different places and must never be mixed:
  - **Implemented** → handed to the developer agents in Step 3; they write these.
  - **Verified at review time** (E2E, stress) → executed by **you** in Step 5. **No agent implements these and nothing for them is committed.**
- If the ticket has no `## Testing` section, derive a minimal one yourself in the same shape before Step 3, and say so in the final summary.

Otherwise, use `$ARGUMENTS` directly as the task description, and infer the agents to spawn from what the task implies.

## Step 1 — Load context

Do this in parallel:

1. **System Design** — read `doc/system_design.md`. Use Grep to locate the sections relevant to this task (its feature, endpoints, data model); don't read unrelated parts end-to-end. This is the authoritative behavior reference.
2. **Affected code** — from the task's technical solution, Glob/Grep the codebase for the packages/files it touches, and note them so the agents and reviewer start in the right place. If the code doesn't exist yet (new feature), note the package it should live in.

## Step 2 — Agents

Spawn one agent per entry in the task's **`suggested-agents`** frontmatter, by name. If a definition for that name exists under `.claude/agents/` (e.g. `backend-developer.md`), spawn it as the `subagent_type` so its coding conventions apply. If there is no definition, you do **not** need one — spawn a base `general-purpose` agent and tell it to **act as that named role**; the role name + the task body + the codebase is enough. Either way the Step 3 prompt's **Your role** line carries the name. `*-code-reviewer` is **reserved** for the review loop in Step 4 — never spawn it as a primary agent.

## Step 3 — Spawn primary agents

Spawn the agents in **parallel** — a single message with one `Agent` tool call per agent.

**IMPORTANT: Never pass `isolation: "worktree"` to Agent calls.** All agents work directly in the main working tree; omit the `isolation` parameter entirely.

Each agent gets this prompt body (substitute the affected packages from Step 1):

> **Task:** $ARGUMENTS
>
> **System Design:** the authoritative reference is `doc/system_design.md` — Grep the sections relevant to your work before deciding behavior; don't read it end-to-end.
>
> **Conventions:** follow the selected agents convetions.
>
> **Your role:** {agent name}. Implement the parts of the task that fall in your specialty. If parts are outside it, leave them to the other agent(s) running in parallel — name what you're not doing in your final summary so nothing falls through the cracks.
>
> **Tests to write:** {the "Implemented" lines from the ticket's `## Testing`, verbatim, filtered to your specialty}. Write exactly these — no more, no less. **Do not write end-to-end or stress/load tests** (no Playwright specs, no Gatling simulations, no load scripts, no dependencies for them); those are executed live at review time and nothing for them is committed.
>
> **Report back:** files created/modified (absolute paths), what you did, and anything ambiguous you had to decide.
>

Wait for all agents to return. **Collect** the union of files they touched and a brief per-agent summary.

## Step 4 — Code review loop (cap: 3 rounds)

Spawn the proper **`*-code-reviewer`** agent(s) (`subagent_type: *-code-reviewer`, defined in `.claude/agents/`) — **do not** substitute a `general-purpose` agent. Pick by the area the files touched: backend files → `backend-code-reviewer`, frontend files → `frontend-code-reviewer`. If the round touched **both**, spawn **both in parallel**, each given only the files in its area, and merge their findings (the round is `CLEAN` only if every reviewer returns `STATUS: CLEAN`).

Their review criteria and the exact output format (`STATUS: CLEAN` / `STATUS: NEEDS_FIX`, findings tagged `[BLOCKER]` with `file:line` + source) live in their definitions, so the prompt only needs the change under review:

> **Task that was implemented:** $ARGUMENTS
>
> **Files touched in this round:** {list of absolute paths from Step 3 / previous round, in your area}
>
> **Tests the ticket required:** {the "Implemented" lines from the ticket's `## Testing`, verbatim}
>
> Review these against `doc/system_design.md` (Grep the relevant sections) and the project conventions, per your definition. **Report findings only — do not edit.**

**Loop logic** (max 3 rounds total, including the first review):

1. If `STATUS: CLEAN` → exit loop, go to Step 5.
2. Otherwise, route each finding to the agent that owns that file's specialty.
3. Re-spawn the responsible agent(s) **in parallel**:
   > **Original task:** $ARGUMENTS
   > **Reviewer findings to address:** {findings owned by this agent, verbatim}
   > **Constraint:** fix only what the reviewer flagged. Do not refactor surrounding code or add new features.
4. Re-spawn the reviewer(s) with the (possibly expanded) file list and the same instructions.
5. After 3 total rounds, if still `NEEDS_FIX`, exit and surface the remaining findings to the user — do not silently swallow them.

## Step 5 — End-to-end & stress verification

This step runs the **"Verified at review time"** half of the ticket's `## Testing` — the E2E and stress scenarios. Nothing else belongs here: unit/integration/component tests were already written and run by the developer agents in Step 3, so **do not re-run the unit suites and do not detect build tooling**. You drive these scenarios live against the running stack; **you write no test files into the repo** — any throwaway script goes in the session scratchpad and is never committed.

### 5a — Bring the stack up

Start the real stack the way the design specifies (Docker Compose: `postgres` + `backend` + `frontend`), wait until it is healthy, and note the base URL. If the stack cannot start, that is a **FAILED** outcome — print the relevant error lines and stop; don't fall back to mocks, and don't attempt open-ended fixes unless obviously part of the task.

### 5b — End-to-end (browser-driven)

For each **E2E** line in the ticket's Testing section:

1. Drive a **real browser** through the scenario using the configured browser-automation MCP (Playwright MCP, or the `claude-in-chrome` skill's tools if that's what this session has). Do not simulate the UI with `curl` — if no browser MCP is available, record E2E as **SKIPPED (no browser MCP configured)** rather than substituting API calls.
2. Follow the user's steps exactly as written, and assert the **observable outcome** the line names (what appears on screen), not an internal value.
3. Check the browser console and network panel for errors while you're there; a 500, an unhandled rejection, or an `/api/v1/*` response served from cache is a finding.
4. If the ticket says `E2E: none`, record **SKIPPED (none required)** and move on.

### 5c — Stress / concurrency

For each **Stress** line in the ticket's Testing section:

1. Generate the load **ad hoc** against the running API — a throwaway script in the scratchpad firing N concurrent requests is the right tool. Do **not** add a Gatling simulation, a load-test module, or any dependency to the repo.
2. The point is the **invariant**, not the RPS number: fire the concurrent requests the line describes and then verify the guarantee held — e.g. exactly one of N simultaneous starts succeeded and the rest got the expected conflict, and the database holds exactly one row.
3. Report what actually happened: requests fired, the response-code distribution, and the post-run state you checked. A broken invariant is a **FAILED** outcome and goes to the user verbatim.
4. If the ticket says `Stress: none`, record **SKIPPED (none required)** and move on.

### 5d — Report

Record a per-scenario outcome (E2E: SUCCESS / FAILED / SKIPPED, Stress: SUCCESS / FAILED / SKIPPED) and surface every failure in the Step 6 summary — never silently swallow one. Tear the stack down when you're done.

## Step 6 — Final summary

Output to the user, tight — one short paragraph plus the file list:
- Agents used (primary + which reviewer(s) + how many review rounds).
- Files created / modified, as absolute paths.
- Final reviewer status (`CLEAN` or `NEEDS_FIX after 3 rounds — open items: …`).
- E2E outcome (SUCCESS / FAILED / SKIPPED + one line why) and stress outcome (same), from Step 5.
- Spec updates applied (which section, one line each) — or "no spec changes needed".
- Anything ambiguous the agents or spec step flagged.
- **If the task came from `@<file>`**: hint the user to close it by editing its `status: open` → `status: fixed`. Do **not** auto-close it.
