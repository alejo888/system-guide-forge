# Agent Guidelines

This repository uses MCP and local agents to inspect local systems safely. Keep all inspected-system work read-only and keep generated browser artifacts local.

## Quick path

1. Use only the pinned Playwright MCP server for local or explicitly authorized targets.
2. Inspect with read-only browser actions; stop when an action could mutate state or its effect is unclear.
3. Run the focused validation for the changed area and discard disposable browser artifacts.

## Authorized usage

- **Playwright MCP:** use the project-local `.mcp.json` configuration and the pinned `@playwright/mcp@0.0.79` package for browser inspection and UX reference work.
- **Stitch:** use only as a temporary UX/UI design reference; it is not an MVP runtime dependency.
- **Target scope:** prefer `test-target/` and local development services. Access another system only with explicit authorization.
- **Mutation boundary:** do not submit forms, change settings, create or delete records, upload files, send messages, or perform other mutations against inspected systems. Treat unknown actions as blocked.

## Artifact and secret handling

- `.playwright-mcp/` is local-only and excluded through `.git/info/exclude`. It may contain disposable logs or snapshots and must remain empty or untracked.
- Never place passwords, API keys, tokens, cookies, session data, or other credentials in prompts, screenshots, logs, fixtures, or committed files.
- Do not retain screenshots or logs containing sensitive data. Delete or securely discard them after inspection; do not copy them elsewhere.
- Use fixture credentials only through the authorized test flow and never treat them as reusable secrets.

## Validation

| Area | Command | Condition |
| --- | --- | --- |
| Backend | `cd backend && ./mvnw test` | Present Surefire reports record 89 tests, 0 failures, 0 errors, and 0 skipped; this is not a command execution claim for the current session |
| Frontend | `cd frontend && npm test` | The current source declares 36 `it` cases; this is not a command execution claim for the current session |
| Frontend build | `cd frontend && npm run build` | Run when the frontend changes; no current-session result is asserted |
| Fixture smoke test | `cd test-target && npm test` | Does not require the backend |
| Fixture E2E | `cd test-target && npm run e2e` | Requires PostgreSQL, backend, fixture, Java 25, and Chromium installed with the Playwright Java CLI |
| Markdown/configuration | `git diff --check` and Markdown/YAML validation | Run according to the change |

Fixture/E2E validation and manual browser inspection require the local stack; they are not considered executed by a documentation-only update.

## Recommended roles and skills

- `security-audit`: review credential exposure, unsafe boundaries, and configuration risks without modifying files.
- `browser-safety-audit`: verify browser workflows remain read-only and artifacts are handled safely.
- `ux-reference`: compare local UI behavior with approved Stitch references without changing the target system.

Use the narrowest read-only tools needed: `read`, `grep`, and `find`. Keep implementation, mutation, and secret-handling work outside these agents and under explicit human control.
