# RESUME: where to pick up

_Last updated: 2026-11-06_

## Current state
- Public repo: https://github.com/AmitPokhrel05082002/skeleton-key (MIT license, branch `main`). Project name: **Skeleton Key**, Java package `dev.skeletonkey`.
- `schema/app-definition.schema.json` v1 drafted. `examples/grievance.app.json` passes it, and 11 broken variants fail it (Ajv, draft 2020-12).
- Steering written: `.kiro/steering/{product,tech,structure,app-definition}.md`. `app-definition.md` pulls the schema in with `#[[file:...]]`.
- Kiro spec `.kiro/specs/skeleton-backend/`: requirements.md and design.md approved after review (status precedence, auto-publish, 22 properties). tasks.md generated and revised. **Task 1 (foundation) done**: Maven project in skeleton/backend (Boot 3.5.16), Flyway V1 (tested on PostgreSQL), web foundations. Next: task 2.1 (domain model).

## Next action
1. Credits received (4,000, expire Dec 31). Track usage in Kiro → Settings → Account & plan.
2. In Kiro, run tasks one at a time (2.1 next). After each: review, `mvnw.cmd -q test` in skeleton/backend, commit, log in PROGRESS_TRACKER. Build needs JAVA_HOME = jdk-21; Docker Desktop must run for Testcontainers tasks. (Original spec prompt:) *"Build the skeleton backend
   described by the steering docs: metadata loading, the publish-time validator, the dynamic records API and the
   workflow routing pipeline. Must-have scope only."* Let Kiro write requirements.md → design.md → tasks.md and
   review each before approving.
3. Plan a second spec `engine-extensions` (~Nov 13–17): SLA scheduler + escalation, parallel/join, sub-workflow, file upload, notifications. Both apps depend on it.

## Decisions made
- Keys are camelCase (`slaHours`, `onExit`) to map 1:1 onto Java and TypeScript.
- `appliesWhen` dropped; `skipWhen` alone covers it (rule: no redundant config).
- Sub-workflow is its own step type (`SUBWORKFLOW`) instead of an event.
- Routing precedence: matching transitions by priority → `action.to` → next step in order.
- Tenant override is not part of the definition (deployment concern, "Nice" tier).
- Expressions: JSONLogic only.
- Versioning is per whole app definition document (app code + version, assigned by the backend). `workflow.version` removed from the schema.
- Frontend spec must include a minimal drag-and-drop form builder (add field, pick type, reorder, live preview) writing the same JSON; JSON editor stays as the fallback. It is a key 20-second demo moment.
