# Implementation Plan: User Admin and Tracking

## Overview

Java 21 / Spring Boot 3 backend (package `dev.skeletonkey`) and Angular + NG-ZORRO frontend. Paths are relative to `skeleton/backend/` or `skeleton/frontend/` as stated. One Flyway migration, `V3__username_case_insensitive.sql`. Commands use Windows CMD syntax, run from `skeleton\backend` (`mvnw.cmd test`) or `skeleton\frontend` (`npx ng test --watch=false`). Source files are UTF-8 without BOM with LF line endings; use the editor file tools, not `Set-Content`.

Part A (tasks 1 to 5) and Part B (tasks 6 to 12) are independent and can be done in either order. The schema change (task 6) touches the shared schema and must update the Java model, TypeScript types, Validator, `examples/` and the steering doc together. Property tests marked with `*` are optional except those the requirements call out; the Testcontainers tests are required because the new SQL must run on real PostgreSQL.

Status note (2026-11-18): every Testcontainers test is in `web/UserAdminAndTrackingIT` (tasks 1.3, 2.3, 2.6, 7.4 and the PostgreSQL part of 7.5). It passed 5/5 on PostgreSQL 16 (Testcontainers), together with the two existing Docker-based test classes.

## Tasks

- [x] 1. Part A: persistence for user administration
  - [x] 1.0 Case-insensitive usernames and active check in the repository
    - Add `V3__username_case_insensitive.sql` (unique index on `lower(username)`); `findByUsername` compares `lower(username) = lower(:u)` in `JdbcUserRepository` and `equalsIgnoreCase` in `InMemoryUserRepository` (which also rejects case-insensitive duplicates); add `boolean isActive(UUID)` to `UserRepository` (`SELECT active FROM app_user WHERE id = :id`, missing = false) in both implementations
    - _Requirements: 3.3, 3.4, 5.5, 5.7_
  - [x] 1.1 Extend `UserRepository` and `JdbcUserRepository`
    - `UserRow`, `UserSearch` (closed `Field` enum for sorting, `likePattern` escaping), `PageResult`, `search`, `findRow`, `update(id, displayName, active, roles)`, `updatePasswordHash`
    - Search: `ILIKE ... ESCAPE '\'`; role via `EXISTS`; active filter; `ORDER BY <enum column>, id`; count with the same WHERE; roles for the page in one query. Filters are appended only when present, so no untyped NULL parameter reaches PostgreSQL
    - Never select `password_hash` in the admin queries
    - _Requirements: 2.1 to 2.8, 3.7, 4.1, 4.4, 4.6_
  - [x] 1.2 Update `InMemoryUserRepository` (test sources) with the same methods
    - _Requirements: 13.2_
  - [x] 1.3 Testcontainers test for the user SQL (written in `web/UserAdminAndTrackingIT`, passed)
    - Search by username and display name, `%`/`_`/`\` taken literally, role filter, active filter, combined filters, stable paging, role replacement, password reset, V3 unique index
    - _Requirements: 2.4 to 2.8, 3.4, 4.1, 4.4, 13.2_

- [x] 2. Part A: services
  - [x] 2.1 `RoleOptionsService` in `app/`
    - _Requirements: 6.1 to 6.6_
  - [x] 2.2 `UserAdminService` in `app/`
    - _Requirements: 3.1 to 3.8, 4.1 to 4.8, 5.4_
  - [x] 2.3 Testcontainers deactivation test (written in `web/UserAdminAndTrackingIT`, passed)
    - Deactivate through the API; LEAST_LOADED (TRIAGE) and ROUND_ROBIN (resolver on the real repository) never pick the deactivated user; the existing task keeps its assignee and is shown to the supervisor with `assigneeName`; login of the deactivated user fails; reactivation works
    - _Requirements: 5.1 to 5.3, 5.6, 13.2_
  - [x] 2.5 Active check in `JwtAuthFilter`
    - `ActiveUserCheck`, `ActiveUserCache` (TTL `skeletonkey.auth.active-cache-seconds`, max 10 000 entries, `Clock`, `evict`, fails closed), `JwtAuthFilter(JwtService, ActiveUserCheck)`, `SecurityConfig` via `ObjectProvider`; `UserAdminService.update` evicts on `active` changes
    - Tested in `web/UserAdminApiTest`: token rejected on the next request after deactivation, accepted again after reactivation, cache expiry with a controlled clock, unknown user rejected
    - _Requirements: 5.5_
  - [x] 2.6 Case-insensitive username tests
    - In-memory API test done (`UserAdminApiTest.usernamesAreCaseInsensitiveForUniquenessAndLogin`); the PostgreSQL unique-index part is in `UserAdminAndTrackingIT` (passed)
    - _Requirements: 3.4, 5.7_
  - [ ]* 2.7 Property tests for 5.5 and 5.7 (Properties 15, 16). Covered by example tests only so far
    - **Validates: Requirements 3.4, 5.5, 5.7**
  - [ ]* 2.4 Property tests (jqwik)
    - [x] `router/DeactivatedNeverAssignedPropertyTest` (Property 4)
    - [ ] Properties 6, 7, 12 as property tests (covered by example tests in `UserAdminApiTest` so far)
    - **Validates: Requirements 3.2 to 3.6, 4.3, 5.2, 5.4, 6.2 to 6.6**

- [x] 3. Part A: web layer
  - [x] 3.1 DTOs and `web/UserAdminController`
    - _Requirements: 1.1 to 1.4, 2.1 to 2.3, 2.9, 2.10, 3.1, 4.1, 4.6_
  - [x] 3.2 Web tests (`web/UserAdminApiTest`, 15 tests)
    - _Requirements: 1.1 to 1.4, 2.3, 3.4 to 3.6, 4.2, 4.5, 5.4_
  - [ ]* 3.3 Property test `web/UserApiNoHashPropertyTest` (Property 5). Example assertions exist in `UserAdminApiTest` and `UserAdminAndTrackingIT`
    - **Validates: Requirements 2.10**

- [x] 4. Checkpoint, Part A backend (all non-Docker tests pass)

- [x] 5. Part A: frontend
  - [x] 5.1 API types and client methods
  - [x] 5.2 Pure model `features/admin/users/user-form-model.ts`
  - [x]* 5.3 Property test `user-form-model.property.spec.ts`
  - [x] 5.4 Components (users page, drawer, reset-password modal)
  - [x] 5.5 Routing and header link
  - [x] 5.6 Component specs (`users-page.component.spec.ts`: render, debounce, confirm with hint, cancel, failure keeps state, own row disabled, 403). Drawer and reset-modal specs not written yet
    - _Requirements: 7.1 to 7.9_

- [x] 6. Part B: schema, model, validator, docs
  - [x] 6.1 Schema and model (`Entity.Tracking`, parser, restricted projection)
  - [x] 6.2 `TrackingRules` validator group
  - [x] 6.3 Public_Subset `trackingEnabled` (allow-list test extended)
  - [x] 6.4 Example, TypeScript types, steering doc prose (schema file only referenced, not embedded)
  - [x]* 6.5 `domain/validator/TrackingRulesPropertyTest` (Property 10, Property 11 parse part, schema rejects malformed tracking); `DefinitionArbitraries` generates valid tracking
    - _Requirements: 9.1 to 9.7_

- [x] 7. Part B: tracking lookup
  - [x] 7.1 `persistence/TrackingRepository` + `JdbcTrackingRepository`
    - Timeline includes `START` and every movement row (`ACTION`, `SKIPPED`, `AUTO`) with a changed status; see requirement 8.8 for why. Whitespace trimmed consistently (space, tab, CR, LF) in Java and SQL
    - _Requirements: 8.3, 8.7, 8.8_
  - [x] 7.2 `app/TrackingService`
  - [x] 7.3 `web/TrackController`, security and errors (`Cache-Control: no-store` set by the interceptor for every outcome)
  - [x] 7.4 Testcontainers tracking SQL test (written in `web/UserAdminAndTrackingIT`, passed)
  - [x] 7.5 Web and end-to-end tests
    - MockMvc part done (`web/TrackEndpointTest`, 7 tests); PostgreSQL end-to-end part in `UserAdminAndTrackingIT` (passed)
  - [x]* 7.6 `app/TrackingServicePropertyTest` (Properties 1, 2, 3, 8, in-memory repository)
    - _Requirements: 8.1 to 8.11_

- [x] 8. Part B: rate limiting
  - [x] 8.1 Limiter, trusted proxies, configuration, compose port bound to loopback
  - [x] 8.2 Interceptor and error mapping
  - [x] 8.3 Tests (`TrackEndpointTest`: 11th request, malformed body counted, lookup not run, other address and other endpoint unaffected, untrusted `X-Real-IP` ignored, trusted used)
  - [x]* 8.5 `config/ClientIpPropertyTest` (Property 17)
  - [x]* 8.4 `config/TrackRateLimiterPropertyTest` (Property 9)
    - _Requirements: 10.1 to 10.9_

- [ ] 9. Checkpoint, Part B backend
  - All backend tests pass, including the Docker-based ones. `docker compose up` not verified: the `postgres:16` pull was blocked by the network

- [x] 10. Part B: frontend track page
  - [x] 10.1 Types, client method and pure model
  - [x]* 10.2 `track-model.property.spec.ts` (Properties 13, 14)
  - [x] 10.3 Track page and route
  - [x] 10.4 Success link in `public-form.page.ts`
  - [x] 10.5 Component specs (`track.page.spec.ts`, `public-form.page.spec.ts`)
    - _Requirements: 11.1 to 11.8, 12.1 to 12.4_

- [x] 11. Documentation
  - "Running locally" section in the root `README.md`: prerequisites, env vars, compose, seed file for the first admin, demo URLs, trusted proxies and loopback binding, deactivation and role-change behaviour, dev setup
  - _Requirements: 5.5, 10.2, 10.9_

- [ ] 12. Final checkpoint
  - [x] `mvnw.cmd test` for the Docker-based tests: `UserAdminAndTrackingIT` 5/5, `FrontendBackendSupportIT` 7/7, `ApplicationContextTest` 6/6 (2026-11-18)
  - [x] `npx ng test --watch=false` (331/331), `npx ng build`, `npx ng lint`
  - [ ] Manual smoke test on the full stack (create user, deactivate, login fails; submit, track, 429). Blocked (2026-11-18): the `postgres:16` image is now pulled but corrupt on this machine (`/lib/x86_64-linux-gnu/libc.so.6: invalid ELF header`), so the postgres container never becomes healthy. Fix: `docker rmi postgres:16` then `docker pull postgres:16`, then rerun `docker compose up -d --build`
