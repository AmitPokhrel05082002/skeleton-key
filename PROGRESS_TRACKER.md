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
