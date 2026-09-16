# E2E stack orchestration

## Goal
Provide one command that starts the local E2E prerequisites, waits for readiness, runs the backend E2E suite, and cleans up only processes/resources it started.

## Tasks
- [x] Add cross-platform Node orchestration script for PostgreSQL, backend, fixture, readiness, E2E execution, and cleanup.
- [x] Add `npm run e2e:stack` entry point.
- [x] Document the automated command and its prerequisites.
- [x] Verify successful execution and cleanup behavior.

## Constraints
- Preserve existing manual startup commands.
- Do not kill or stop services that were already running before the command.
- Keep technical artifacts in English.
