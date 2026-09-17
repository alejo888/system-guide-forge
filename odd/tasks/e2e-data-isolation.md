# E2E data isolation

## Goal
Run stack-managed E2E tests against a dedicated disposable database without mutating a developer's normal local database.

## Tasks
- [x] Reset and target a dedicated database in the stack runner.
- [x] Clean up the dedicated database after the run without stopping pre-existing PostgreSQL.
- [x] Document the isolation boundary and direct-browser limitation.
- [x] Run focused verification and review the final diff.

## Constraints
- Preserve existing manual startup workflows.
- Never reset or drop the developer's normal `systemguideforge` database.
- Keep technical artifacts in English.
