---
inclusion: always
---

# Product

**Skeleton Key** is a lean low-code **skeleton for workflow apps**. An app is data, not code: one versioned JSON
app definition (see `app-definition.md`) describes roles, entities, forms, views and workflows.
The skeleton runs any valid definition without new code.

Two distinct apps are built from the same skeleton to prove it is reusable:
- `app-grievance/`: citizen grievance tracker (public form, conditional routing, SLA escalation).
- `app-assets/`: asset register (parallel review, maintenance sub-workflow, QR check-in, custom plugin action).

## Principles
- **Lean over complete.** Each feature must be needed by at least one of the two apps.
- **Every config field must do something.** Never add a definition property that the engine ignores.
- **One code path.** Every workflow action goes through the same routing pipeline.
- **The escape hatch is a plugin**, not a fork: apps extend through `ActionHandler` beans and Angular widgets only.
- Apps must never edit files under `skeleton/`.
