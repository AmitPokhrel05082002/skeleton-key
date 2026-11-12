---
inclusion: always
---

# Tech stack and conventions

## Backend (`skeleton/backend`)
- Java 21, Spring Boot 3.x, Maven. Package root `dev.skeletonkey`.
- Config in **`application.properties` only**, never YAML. Secrets come from environment variables (`${DB_PASSWORD}`).
- PostgreSQL 16. Schema changes go through Flyway (`db/migration/V{n}__desc.sql`). Business records are stored in a
  `data JSONB` column, so a new field in an app definition never needs a migration.
- Security: Spring Security with stateless JWT. Authorisation is checked in the engine against the definition
  (roles per action, field rules per step), not only with `@PreAuthorize`.
- Expressions: JSONLogic only (one evaluator, used for `when`, `skipWhen` and `SET_FIELD` values). No SpEL, no scripting.
- Errors: one `@RestControllerAdvice` (`ApiExceptionHandler`) that returns the design's `ApiError` JSON: status, code, message, requestId, violations[] (field, rule, message), plus line/column for malformed JSON. 5xx bodies never contain internal detail.
- API: REST under `/api/**`, OpenAPI via springdoc. Lists are paginated (`page`, `size`, `sort`) and filterable.
- Tests: JUnit 5, Testcontainers for Postgres, **jqwik** for property-based tests of the validator and the engine.
- Layering per feature package: `web` (controllers, DTOs) → `service` → `repository`. No business logic in controllers.

## Frontend (`skeleton/frontend`)
- Angular (latest stable) with standalone components and signals. UI library: **NG-ZORRO** only, no second UI kit.
- Renderers are driven by the definition: `DynamicFormComponent`, `DynamicTableComponent`, `KanbanComponent`,
  `RecordDetailComponent`. Never hard-code an app's fields in a component.
- Custom widgets are registered via an injection token (`FIELD_WIDGETS`) so apps add widgets without touching the skeleton.

## Runtime
- `docker compose up` must start everything (db, backend, frontend, reverse proxy) with seed data.
- JVM capped with `-Xmx384m`. Every service has `restart: unless-stopped` and a healthcheck.

## Commands
The developer works in **Windows CMD**. When giving shell commands use CMD syntax: `^` for line continuation,
`findstr` instead of `grep`, `set VAR=value`, and escaped double quotes inside JSON (`"{\"a\":1}"`).
Prefer `mvnw.cmd` and `npm`/`npx` commands that work the same on Windows and Linux.

## File encoding
Write every source file as **UTF-8 without BOM** and with LF line endings. In PowerShell 5.1 never use
`Set-Content`/`Out-File` for source files (they add a BOM, which breaks `javac`); use the editor's file tools instead.
