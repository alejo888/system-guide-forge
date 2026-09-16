# Frontend browser E2E

## Goal
Add a real browser-level Playwright flow for the Angular UI, covering registration, access verification, analysis completion, and rendered evidence against the deterministic fixture.

## Tasks
- [x] Add Playwright test tooling and a stable browser E2E spec.
- [x] Extend the E2E stack runner to start and wait for Angular when browser validation is requested.
- [x] Document the browser E2E command and prerequisites.
- [x] Run the browser flow and record evidence.

## Constraints
- Keep the existing API E2E command unchanged.
- Use a fresh browser context and semantic/stable selectors.
- Preserve manual startup workflows.
