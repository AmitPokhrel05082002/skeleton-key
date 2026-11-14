# Progress tracker

Submission closes **Dec 4, 2026, 2:00 PM PT**. Target: submit Dec 3.

## Milestones
| Week | Goal | Status |
|---|---|---|
| Nov 6 | Kiro credits requested, repo + license, steering, schema | 🟡 schema, steering, public repo (MIT) done, credits received |
| Nov 6–12 | Engine spec (in Kiro), backend core, validator, routing | ⬜ |
| Nov 13–19 | Angular shell + renderers, app #1 (grievance) | ⬜ |
| Nov 20–26 | App #2 (timed), PBT, polish, first deploy | ⬜ |
| Nov 27–Dec 3 | Final deploy, video, blog, social post, Kiro write-up, submit | ⬜ |

## Submission checklist
- [x] Kiro credits received: 4,000 bonus credits "kiroween-2026" (expire Dec 31, 2026)
- [x] Public GitHub repo, license shown in About
- [ ] `.kiro/` committed and not ignored
- [ ] `app-grievance/` and `app-assets/` as separate folders
- [ ] Both apps live over HTTPS, test logins in the Devpost form
- [ ] Video < 3 min on YouTube (public), no copyrighted music
- [ ] Kiro write-up, blog post (mentions Kiroween 2026), social post starting with #KiroweenHackathon26
- [ ] Pre-existing code / tools disclosed

## Tool usage log (feeds the Kiro write-up, so keep it honest)
| Date | Tool | What | Outcome |
|---|---|---|---|
| 2026-11-06 | Claude Code | Drafted JSON Schema v1, grievance example, steering docs; validated the schema with Ajv (1 valid + 9 invalid cases) | Inputs ready for Kiro specs |
| 2026-11-07 | Kiro IDE (Spec mode) | Generated `workflow-engine` requirements.md (17 reqs) | Solid, but too big and had a few inconsistencies |
| 2026-11-07 | Claude Code | Reviewed requirements: found 6 defects (false PBT property on step permutation, duplicate drafts on restart, inconsistent history kinds, continuity vs event entries, versioning unit, audit of non-existent delete), cut scope, added 3 missing endpoints/features | Fed back to Kiro as one revision prompt |
| 2026-11-07 | Kiro IDE (Spec mode) | Applied all 10 revisions in place (now 20 reqs) | Approved |
| 2026-11-07 | Claude Code | Removed `workflow.version` from schema/example, versioning moved to whole document; updated steering | Example still valid, 9 invalid cases still rejected |
| 2026-11-07 | Kiro IDE (Spec mode) | Generated design.md (pure core + ports, 26 properties) | Good base; 5 bugs/gaps found in review |
| 2026-11-07 | Claude Code | Reviewed design: missing step_entered_at, cursor outside transaction, roleless forms unusable, no startup auto-publish, stale status after reroute/skip; cut 5 non-PBT-worthy properties and 2 over-engineered parts; suggested json-logic-java for evaluation | 9-point revision prompt to Kiro |
| 2026-11-07 | Kiro IDE (Spec mode) | Revised requirements + design (status precedence, UNSET_STATUS, auto-publish, 22 properties); design update timed out once and Kiro repaired the markdown | Accepted |
| 2026-11-07 | Claude Code | Schema: optional `status` on steps and transitions (not on END); example sets "Inspection" via transition; steering documents precedence. Verified json-logic-java 1.1.0 (May 2025) | Example valid, 11 invalid cases rejected |
| 2026-11-07 | Kiro IDE (Spec mode) | Generated tasks.md; revised on review (core 10 jqwik properties required, router and records tasks split, backend in skeleton/backend with JdbcClient, validator checkpoint) | Ready to implement |
| 2026-11-07 | Claude Code | Aligned design.md wording to JDBC-only, moved compose file to repo root in structure.md, re-validated example | Spec consistent |
| 2026-11-07 | Kiro IDE (Spec task) | Task 1.1 verified schema; task 1.2 Maven scaffold in skeleton/backend, compose at root, no committed secrets | Build passes (no tests yet) |
| 2026-11-07 | Claude Code + Kiro | Kiro had pinned year-old versions from memory; checked Maven Central, Kiro updated to Boot 3.5.16, json-logic 1.1.0, networknt 1.5.9, jjwt 0.12.7, jqwik 1.9.3, Boot-managed Flyway 11.7.2 / PG 42.7.11 / Testcontainers 1.21.4 | Resolved versions verified with dependency:list |
| 2026-11-07 | Kiro IDE (Spec task) | Task 1.3 Flyway V1 schema + failure analyzer | Review found record.status duplicate (dual-storage risk), single history status column, nullable instance columns |
| 2026-11-07 | Kiro IDE | Fixed V1 in place per review (status only on instance, from/to status, time_in_step_ms, NOT NULLs, kind CHECK) | Compiles |
| 2026-11-07 | Claude Code | Applied V1 to a throwaway local PostgreSQL 17 cluster and tested 8 behaviours (append-only trigger on UPDATE/DELETE, kind CHECK, time CHECK, NOT NULL, one instance per record, no record.status) | All 8 pass; cluster deleted |
| 2026-11-07 | Kiro IDE (Spec task) | Task 1.4 web foundations (request id, error handler, UTC ms instants, OpenAPI, 405) + 5 MockMvc tests | Pass |
| 2026-11-07 | Claude Code | Review fixes: migrated jqwik.properties to junit-platform.properties (old file was ignored, tries=100 had no effect); RouterException 500s no longer leak internal messages; springdoc 2.8.9 -> 2.8.17 | Clean build, 5/5 pass, no SEVERE warning |
| 2026-11-12 | Kiro IDE (Spec task) | Task 2.1 immutable domain model, parser, cache + 4 tests; PowerShell-written BOMs broke compilation, Kiro stripped them | Model matches schema (step/transition status, no workflow.version, statuses are strings) |
| 2026-11-12 | Claude Code | Verified model vs schema, no BOMs left, full suite 9/9; added "UTF-8 without BOM" rule to tech.md steering so it doesn't recur | Pass |
| 2026-11-12 | Kiro IDE (5 parallel spec tasks) | ExpressionEngine (allow-list + json-logic-java), Validator infra + 3 rule groups, DefinitionLoader (parse/schema/hash), RecordValidator (15 types), 7 ports with JDBC adapters + in-memory twins | Compiles; only loader tests run by Kiro |
| 2026-11-12 | Claude Code | Full suite 13/13; checked every adapter SQL column against V1 (all match), empty IN-list guard, allow-list enforced before evaluation, depth cap 64 | Pass; open: 400/422 mapping for definition parse/schema exceptions |
| 2026-11-12 | Kiro IDE (5 parallel spec tasks) | Workflow graph rules, expression/handler rules, field rules + status resolution + transition selector, assignee resolver, handler registry + EventRunner; 400/422 definition error mappings | 58 tests pass |
| 2026-11-12 | Claude Code | Clean rebuild 58/58; removed no-arg Validator() so publish can't skip expression/handler rules; aligned tech.md error rule with the design's ApiError shape (was ProblemDetail) | Pass |
| 2026-11-12 | Kiro IDE (6 spec tasks) | Records listing + row visibility (SQL + in-memory), router handler/events/selection, 3 required router properties, platform admin role (Req 6.9) guarding definition endpoints, ApplicationContextTest (Testcontainers) | 160 tests pass; context test blocked by Docker Desktop (read-only containerd FS) |
| 2026-11-12 | Claude Code | Verified 160/160 clean; reviewed Kiro's interpretations: accepted 4, flagged visibility clause (d) using latest instead of pinned definition (documented as known limitation) | Pass |
| 2026-11-13 | Kiro IDE (spec tasks) | 9.3 context test on real Postgres 16 (found + fixed max(version) NULL bug), JDBC smoke test; 7.6 router step-entry loop, END, start; router pieces as Spring beans | 184 pass |
| 2026-11-13 | Claude Code | Fixed Docker Desktop (WSL backend); verified 184/184 clean incl. ApplicationContextTest on Testcontainers; confirmed design.md restored intact after an interrupted Kiro run overwrote it | Pass |
| 2026-11-13 | Claude Code | Removed 6 empty stray .java files Kiro left at the repo root in 57d303b; added `/*.java` to .gitignore | Real sources untouched |
| 2026-11-13 | Kiro IDE (spec tasks) | InstanceState startedBy/startedAt; router properties 7.16–7.19; 10.1 WorkflowService (one transaction, pinned version, HISTORY_WRITE_FAILED rollback test on Postgres) | 200 pass |
| 2026-11-13 | **PBT win** | Property 7.18 (history continuity, jqwik) found 2 real router bugs the 20 example tests missed: (1) the ACTION row ended at the final step after skips, so the SKIPPED chain broke; (2) AUTO steps wrote no history row. Fixed: ACTION ends at first target; new AUTO kind (V2 migration, Req 12.13) | Good write-up material |
| 2026-11-13 | Claude Code | Verified 200/200 clean incl. Postgres; deleted Kiro's stray Maven logs; confirmed memory workaround left no committed config | Pass |
| 2026-11-13 | Kiro IDE (4 parallel spec tasks) | 10.2 instance view/actions/history, 10.3 form submit, 10.4 definition read + public subset, 10.6 StartupLoader auto-publish (context test now uses the real loader); -Xmx384m | 232 pass; compose build blocked: Docker DNS resolves Maven Central to a Fortinet filter IP |
| 2026-11-13 | Claude Code | Verified 232/232 clean; root clean | Backend required tasks complete |
| 2026-11-13 | Kiro IDE | App healthcheck; workflow presentation subset in definition read (allow-listed keys, reachability rule, Req 16.8–16.10); checkpoints 4, 8, 11 closed | **skeleton-backend spec complete: all required tasks done, 237 tests** |
| 2026-11-13 | Claude Code | Verified 237/237 clean incl. Postgres | Next: skeleton-frontend spec |
| 2026-11-14 | Kiro IDE (Spec task) | Frontend 1.1 Angular 22.2.6 scaffold, ng-zorro-antd 22.2.0, fast-check 4.10.2, Vitest runner, exact pins, proxy config | build, lint, test pass |
| 2026-11-14 | Claude Code | Verified 0 version ranges, lockfile, no BOM/CRLF, node_modules/dist ignored, 41 files staged only under skeleton/frontend, npm test passes | OK; backend-support spec running in parallel in Kiro Crew |
| 2026-11-14 | **Kiro Crew** | Implemented the whole frontend-backend-support spec (20 required tasks) in parallel with the IDE working on the frontend: /auth/me, workflow summary on records (batched), assigneeName/actorName, app + version lists, widget schema field, POST /definitions/validate with 1 MiB limit. Real-Postgres test caught an ORDER BY bug in findAppCodes that in-memory tests missed | 281 tests (237 + 44) |
| 2026-11-14 | Claude Code | Verified Crew stayed in its folders, schema diff = widget only, steering/example untouched, empty-IN guards on batch SQL, 281/281 clean, example still valid | Pass |
