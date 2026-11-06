---
inclusion: always
---

# Repository structure

```
.kiro/            steering, specs, hooks (always committed)
schema/           app-definition.schema.json (the contract between engine, UI and apps)
examples/         sample app definitions used by tests and docs
skeleton/
  backend/        Spring Boot engine: auth, metadata, dynamic records API, workflow engine, scheduler
  frontend/       Angular shell + NG-ZORRO renderers
  docker/         compose file, proxy config
app-grievance/    app #1: definition/, seed/, branding/, extension/ (one custom handler or widget)
app-assets/       app #2: same layout as app #1
```

Rules:
- An app folder contains **only** its definition, seed data, branding and its small extension. If an app needs a
  change in `skeleton/`, that change must be generic and reusable by the other app too.
- `schema/app-definition.schema.json` is the single source of truth. When it changes, update the Java model,
  the TypeScript types, the publish-time validator and `examples/` in the same change.
