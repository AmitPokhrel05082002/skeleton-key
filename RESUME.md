# RESUME: where to pick up

_Last updated: 2026-11-06_

## Current state
- Public repo: https://github.com/AmitPokhrel05082002/skeleton-key (MIT license, branch `main`). Project name: **Skeleton Key**, Java package `dev.skeletonkey`.
- `schema/app-definition.schema.json` v1 drafted. `examples/grievance.app.json` passes it, and 9 broken variants fail it (Ajv, draft 2020-12).
- Steering written: `.kiro/steering/{product,tech,structure,app-definition}.md`. `app-definition.md` pulls the schema in with `#[[file:...]]`.
- No Kiro spec yet. No code yet.

## Next action
1. Request Kiro credits if not done yet (deadline Dec 1, 12:00 PM PT).
2. Open the folder in Kiro → Specs → new spec "workflow-engine". Prompt Kiro with: *"Build the skeleton backend
   described by the steering docs: metadata loading, the publish-time validator, the dynamic records API and the
   workflow routing pipeline. Must-have scope only."* Let Kiro write requirements.md → design.md → tasks.md and
   review each before approving.
3. Ask Kiro for the jqwik properties as a separate requirement so they show up in the spec.

## Decisions made
- Keys are camelCase (`slaHours`, `onExit`) to map 1:1 onto Java and TypeScript.
- `appliesWhen` dropped; `skipWhen` alone covers it (rule: no redundant config).
- Sub-workflow is its own step type (`SUBWORKFLOW`) instead of an event.
- Routing precedence: matching transitions by priority → `action.to` → next step in order.
- Tenant override is not part of the definition (deployment concern, "Nice" tier).
- Expressions: JSONLogic only.
