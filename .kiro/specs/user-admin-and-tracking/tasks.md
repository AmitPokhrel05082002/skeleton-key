# Implementation Plan: User Admin and Tracking

## Overview

Java 21 / Spring Boot 3 backend (package `dev.skeletonkey`) and Angular + NG-ZORRO frontend. Paths are relative to `skeleton/backend/` or `skeleton/frontend/` as stated. No Flyway migration is planned (see design); if one becomes necessary it is `V3__*.sql`. Commands use Windows CMD syntax, run from `skeleton\backend` (`mvnw.cmd test`) or `skeleton\frontend` (`npx ng test --watch=false`). Source files are UTF-8 without BOM with LF line endings; use the editor file tools, not `Set-Content`.

Part A (tasks 1 to 5) and Part B (tasks 6 to 12) are independent and can be done in either order. The schema change (task 6) touches the shared schema and must update the Java model, TypeScript types, Validator, `examples/` and the steering doc together. Property tests marked with `*` are optional except those the requirements call out; the Testcontainers tests (1.3, 2.3, 7.4, 7.5) are required because the new SQL must run on real PostgreSQL.

## Tasks

- [ ] 1. Part A: persistence for user administration
  - [ ] 1.1 Extend `UserRepository` and `JdbcUserRepository`
    - Add `UserRow`, `UserSearch`, `UserSort` (closed enum of sort fields), `search`, `findRow`, `update(id, displayName, active, roles)`, `updatePasswordHash`
    - Search: `ILIKE ... ESCAPE '\'` with `%`, `_`, `\` escaped in `q`; role via `EXISTS`; active filter; `ORDER BY <enum column>, id`; count query with the same WHERE; roles for the page in one query
    - Update: `COALESCE` for name and active, delete and re-insert roles only when roles are given, one `@Transactional` method in the service layer
    - Never select `password_hash` in the admin queries
    - _Requirements: 2.1 to 2.8, 3.7, 4.1, 4.4, 4.6_
  - [ ] 1.2 Update `InMemoryUserRepository` (test sources) with the same methods
    - Same ordering, filtering and case-insensitive substring semantics as the SQL
    - _Requirements: 13.2_
  - [ ] 1.3 Testcontainers test `persistence/jdbc/JdbcUserRepositoryAdminTest`
    - Search by username and display name, `%`/`_`/`\` taken literally, role filter, active filter, combined filters, stable paging and sort in both directions, `total` over the filtered set
    - Role replacement, `COALESCE` leaves absent values alone, duplicate username raises `DuplicateKeyException`, password update changes only the hash
    - _Requirements: 2.4 to 2.8, 3.4, 4.1, 4.4, 13.2_

- [ ] 2. Part A: services
  - [ ] 2.1 `RoleOptionsService` in `app/`
    - Union of non-public roles of the latest PUBLISHED definition per app (`DefinitionQueryService` for app codes, `PublishedDefinitions.latestPublished`), plus the admin role with label `Platform administrator`; sorted; label from the smallest app code
    - _Requirements: 6.1 to 6.6_
  - [ ] 2.2 `UserAdminService` in `app/`
    - `search`, `get`, `create`, `update` (map-based partial update, unknown keys rejected, role check against assignable roles plus roles already held, self-lockout check before any write), `resetPassword`
    - Validation collects every violation (`ValidationFailed`); password never appears in messages; `DuplicateKeyException` -> `Conflict("USERNAME_TAKEN")`; password 8 to 72 UTF-8 bytes; audit entries through `AuditService` (no secrets)
    - _Requirements: 3.1 to 3.8, 4.1 to 4.8, 5.4_
  - [ ] 2.3 Testcontainers test `app/DeactivationAssignmentIT`
    - Deactivate a user through `UserAdminService`; start workflows with `LEAST_LOADED` and `ROUND_ROBIN` assignees on the same role; the deactivated user is never chosen, an all-inactive role gives an unassigned task
    - An existing open task of the deactivated user is still returned in a supervisor `MY_ROLE_QUEUE` view and `InstanceView.assigneeName` still shows the name; deactivated login gives the same 401 as a wrong password
    - _Requirements: 5.1 to 5.3, 5.6, 13.2_
  - [ ]* 2.4 Property tests (jqwik)
    - `app/DeactivatedNeverAssignedPropertyTest` (Property 4, in-memory repository against `AssigneeResolver`)
    - `app/RoleValidationPropertyTest` (Property 6)
    - `app/SelfLockoutPropertyTest` (Property 7)
    - `app/UserCreationPropertyTest` (Property 12)
    - **Validates: Requirements 3.2 to 3.6, 4.3, 5.2, 5.4, 6.2 to 6.6**

- [ ] 3. Part A: web layer
  - [ ] 3.1 DTOs and `web/UserAdminController`
    - Records `UserView`, `UserPage`, `RoleOption`, `CreateUserRequest`; PATCH body bound as a `Map`; `admin.require(RecordController.caller())` first in every method; `Location` header on create; springdoc annotations with `ApiError` responses; no business logic
    - Map `UserRow` -> `UserView` in one place
    - _Requirements: 1.1 to 1.4, 2.1 to 2.3, 2.9, 2.10, 3.1, 4.1, 4.6_
  - [ ] 3.2 Web tests (MockMvc with the existing `AuthSecurityTest` style)
    - Every method answers 401 without a JWT and 403 for a non-admin and changes nothing; 400 for bad paging or sort; 404; 409 `USERNAME_TAKEN` and `SELF_LOCKOUT`; 422 shapes with violations; `role-options` is not read as an id; a non-UUID id gives 400
    - _Requirements: 1.1 to 1.4, 2.3, 3.4 to 3.6, 4.2, 4.5, 5.4_
  - [ ]* 3.3 Property test `web/UserApiNoHashPropertyTest` (Property 5)
    - Serialise responses for random users and passwords; assert no hash, no `$2` value, no password-like key; reflection check that `UserRow` and `UserView` have no secret component
    - **Validates: Requirements 2.10**

- [ ] 4. Checkpoint, Part A backend
  - Run `mvnw.cmd test` in `skeleton\backend`; the existing 237+ tests and the new tests pass. Ask the user if questions arise.

- [ ] 5. Part A: frontend
  - [ ] 5.1 API types and client methods
    - Add `UserView`, `UserPage`, `RoleOption`, `CreateUserRequest`, `UpdateUserRequest` to `core/api/api.types.ts` and `listUsers`, `getUser`, `createUser`, `updateUser`, `resetPassword`, `roleOptions` to `core/api/api.service.ts`
    - _Requirements: 7.2 to 7.6_
  - [ ] 5.2 Pure model `features/admin/users/user-form-model.ts`
    - `validateUserForm` (same rules as Requirement 3.2), `toCreateRequest`, `toPatch(original, edited)` (changed properties only, roles compared as sets)
    - _Requirements: 7.3, 7.4_
  - [ ]* 5.3 Property test `user-form-model.property.spec.ts` (fast-check, Property 14, patch part)
    - `toPatch` is empty iff nothing changed; applying it to the original yields the edited values
    - **Validates: Requirements 7.4**
  - [ ] 5.4 Components
    - `users-page.component.ts`: NG-ZORRO table with server paging and sorting, debounced search, role and active filters, active switch with confirm and rollback, row actions
    - `user-drawer.component.ts`: create/edit, username read-only on edit, password with show/hide on create, role multi-select from `roleOptions()`, field-level error mapping for 409 and 422
    - `reset-password-modal.component.ts`: one password field, clears on close
    - Disable the switch and the admin role option for the signed-in user's row, with a tooltip
    - _Requirements: 7.2 to 7.9_
  - [ ] 5.5 Routing and header link
    - Add the `users` child (lazy `loadComponent`) to `features/admin/admin.routes.ts`; add the "Users" link to `AdminShellComponent`
    - _Requirements: 7.1_
  - [ ] 5.6 Component specs
    - Table render, search debounce, create flow, edit sends only changed properties, deactivate confirm and rollback on error, reset modal, self row disabled, error mapping
    - _Requirements: 7.2 to 7.8_

- [ ] 6. Part B: schema, model, validator, docs
  - [ ] 6.1 Schema and model
    - Add `tracking` to `$defs.entity.properties` in `schema/app-definition.schema.json` (design shape); make sure the classpath copy used by `DefinitionLoader` picks it up
    - Add `record Tracking(String contactField)`; `Entity` gets a sixth component `tracking` and keeps a 5-argument constructor passing null; `DefinitionParser` reads it; read projections keep it for the restricted view
    - _Requirements: 9.1, 9.2_
  - [ ] 6.2 `TrackingRules` validator group
    - `BAD_TRACKING_FIELD`, `TRACKING_FIELD_NOT_EMAIL`, `TRACKING_NEEDS_REFERENCE`; register next to the other `RuleGroup`s; all violations reported
    - _Requirements: 9.3_
  - [ ] 6.3 Public_Subset
    - `trackingEnabled: true` on public forms of tracked entities, `contactField` never exposed; extend the existing allow-list test
    - _Requirements: 9.5_
  - [ ] 6.4 Examples, TypeScript types, steering doc
    - `examples/grievance.app.json`: `"tracking": { "contactField": "contactEmail" }` on `GRIEVANCE`; check `app-assets` needs nothing
    - Frontend definition types: `entity.tracking?: { contactField: string }`, public form `trackingEnabled?: boolean`
    - `.kiro/steering/app-definition.md`: replace the embedded schema copy and add a "Public tracking" behaviour section (lookup rule, uniform 404, timeline content, rate limit)
    - _Requirements: 9.6, 9.7_
  - [ ]* 6.5 Property tests
    - `domain/validator/TrackingRulesPropertyTest` (Property 10), `domain/load/TrackingRoundTripPropertyTest` (Property 11); extend `DefinitionArbitraries` to generate `tracking` for about half of the entities
    - **Validates: Requirements 9.1 to 9.3, 9.5**

- [ ] 7. Part B: tracking lookup
  - [ ] 7.1 `persistence/TrackingRepository` (JdbcClient)
    - `findCandidate`, `contactMatches` (email compared in SQL with `lower(btrim(data ->> :field)) = :email`), `snapshot`, `timeline` (kinds `START` and `ACTION` with a changed status, ordered by `event_time, seq`)
    - _Requirements: 8.3, 8.7, 8.8_
  - [ ] 7.2 `app/TrackingService` and response records
    - Flow of the design; one private `noMatch()` factory for the 404; status colour and category from the instance's pinned workflow; title cut to 200 characters; no logging of reference or email
    - _Requirements: 8.1 to 8.4, 8.7 to 8.9, 8.11_
  - [ ] 7.3 `web/TrackController`, security and errors
    - `POST /api/public/{app}/track`, `Cache-Control: no-store`, validation to 422/400, `SecurityConfig` permit-all for `POST /api/public/*/track`
    - _Requirements: 8.5, 8.6, 8.10_
  - [ ] 7.4 Testcontainers test `persistence/jdbc/JdbcTrackingRepositoryTest`
    - Candidate lookup by app and exact reference; `contactMatches` with mixed case, padding, `%`, `_`, `.*`, missing field and null value; snapshot join; timeline across every history kind (`START`, `ACTION` with and without a status change, `SKIPPED`, `EVENT_FAILED`, `EXPRESSION_ERROR`, `EVENT_DEFERRED`)
    - _Requirements: 8.1, 8.3, 8.4, 8.8, 13.2_
  - [ ] 7.5 Web and end-to-end tests
    - Submit a public form on PostgreSQL, then track: 200 shape; wrong email, unknown reference, other app, tracking disabled, no instance: identical 404; works without JWT and with a garbage `Authorization` header; `Cache-Control: no-store`; 422 and 400 cases
    - _Requirements: 8.1, 8.2, 8.5, 8.6, 8.10_
  - [ ]* 7.6 Property tests
    - `TrackMismatchPropertyTest` (Property 1), `TrackUniform404PropertyTest` (Property 2), `TrackEmailCasePropertyTest` (Property 3), `TrackResponseSafetyPropertyTest` (Property 8); PostgreSQL-backed ones use a shared container and `tries = 25`
    - **Validates: Requirements 8.1, 8.2, 8.4, 8.7 to 8.9**

- [ ] 8. Part B: rate limiting
  - [ ] 8.1 Limiter and configuration
    - `config/TrackRateLimiter` (sliding window per key, access-ordered bounded map, injectable `Clock`), `ClientIp` resolver, properties `skeletonkey.track.rate-limit.per-minute`, `skeletonkey.track.rate-limit.max-clients`, `skeletonkey.track.client-ip-header` in `application.properties` and `SkeletonProperties` with startup validation
    - _Requirements: 10.1, 10.2, 10.5, 10.8_
  - [ ] 8.2 Interceptor and error mapping
    - `TrackRateLimitInterceptor` registered for `/api/public/*/track` only; `TooManyRequests` exception; `ApiExceptionHandler` maps it to 429 `RATE_LIMITED` with `Retry-After`; runs before body parsing
    - _Requirements: 10.3, 10.4, 10.7_
  - [ ] 8.3 Tests
    - Web test: the 11th request in a minute gives 429 with `Retry-After >= 1` and the lookup is not run; malformed bodies are counted; other endpoints are not limited; two addresses (via the configured header) are independent; invalid header falls back to the remote address; startup fails on `per-minute=0`
    - _Requirements: 10.1 to 10.7_
  - [ ]* 8.4 Property test `config/TrackRateLimiterPropertyTest` (Property 9)
    - Compare against a reference model over random (address, time) sequences; bounded key count
    - **Validates: Requirements 10.1, 10.3, 10.5, 10.6**

- [ ] 9. Checkpoint, Part B backend
  - Run `mvnw.cmd test` in `skeleton\backend`; all tests pass. Confirm `docker compose up` still starts (the new properties have defaults). Ask the user if questions arise.

- [ ] 10. Part B: frontend track page
  - [ ] 10.1 Types, client method and pure model
    - `TrackRequest`, `TrackResponse` in `api.types.ts`; `track(app, body)` in `api.service.ts` (no auth header needed; make sure the auth interceptor does not redirect on 404/429 for this call)
    - `features/public-track/track-model.ts`: `validateTrackForm`, `statusChipColor`, `toTimelineItems`
    - _Requirements: 11.2, 11.4_
  - [ ]* 10.2 Property tests `track-model.property.spec.ts` (fast-check, Properties 13 and 14)
    - **Validates: Requirements 11.2, 11.4**
  - [ ] 10.3 `features/public-track/track.page.ts` and route
    - Add `public/:app/track` before `public/:app/:form` in `app.routes.ts`; form, result card with status chip, `nz-timeline`, 404/429/422/other messages, countdown from `Retry-After`, `reference` query prefill, no persistence of email or result, branding from the Public_Subset, text-only rendering
    - _Requirements: 11.1 to 11.8_
  - [ ] 10.4 Success link in `public-form.page.ts`
    - Show "Track this request" (`/public/:app/track?reference=…`) and the fixed hint only when the form has `trackingEnabled`; no email in the link
    - _Requirements: 12.1 to 12.4_
  - [ ] 10.5 Component specs
    - Track page: valid/invalid form, 200 render, 404 single message, 429 disable and countdown, prefill from the query, no storage writes (spy on `localStorage`/`sessionStorage`); public form: link present and absent cases, link target
    - _Requirements: 11.2 to 11.8, 12.1 to 12.3_

- [ ] 11. Documentation
  - Add the new endpoints, properties and the trust note on `X-Real-IP` to the README or the existing run docs; note the JWT-lifetime limitation of deactivation
  - _Requirements: 5.5, 10.2_

- [ ] 12. Final checkpoint
  - Run `mvnw.cmd test` and `npx ng test --watch=false` and `npx ng build`; run the manual smoke test from the design (create user, deactivate, login fails; submit, track, 429). Ask the user if questions arise.
