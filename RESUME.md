# RESUME: where to pick up

_Last updated: 2026-11-06_

## Current state
- Repo folder created (not yet a git repo, not yet on GitHub).
- `schema/app-definition.schema.json` v1 drafted. `examples/grievance.app.json` passes it, and 9 broken variants fail it (Ajv, draft 2020-12).
- Steering written: `.kiro/steering/{product,tech,structure,app-definition}.md`. `app-definition.md` pulls the schema in with `#[[file:...]]`.
- No Kiro spec yet. No code yet.

## Next action
1. Create the GitHub repo **with a license** (Add license → MIT or Apache-2.0), then push this folder.
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
