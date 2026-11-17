# Design Document

## Overview

Two independent slices that share only the existing security setup and `ApiExceptionHandler`.

- Part A adds a user-management feature: `web/UserAdminController` -> `app/UserAdminService` -> `persistence/UserRepository` (extended). A role-options service reads the latest published definitions.
- Part B adds a public lookup: `web/TrackController` -> `app/TrackingService` -> `persistence/TrackingRepository`, with a per-IP rate-limit interceptor in front, one new optional schema property and one Validator rule group.

No Flyway migration is needed. Every column used already exists (`V1__init.sql`): `app_user(username unique, active, display_name, created_at)`, `user_role`, `record(unique (app_code, reference_no), data jsonb, created_at)`, `workflow_instance(updated_at, version, workflow_code, status)`, `history_entry(kind, from_status, to_status, event_time, seq)`.

## Architecture

```
Part A
  AdminUsersPage ──HTTP──> UserAdminController ──> AdminGuard.require
                              │                       (403 unless admin role)
                              ▼
                        UserAdminService ──> RoleOptionsService ──> DefinitionQueryService + PublishedDefinitions
                              │                  (Assignable_Roles = latest published, non-public roles + admin role)
                              ├──> UserRepository (JdbcClient)   ──> app_user, user_role
                              ├──> PasswordEncoder (existing bean)
                              └──> AuditService

Part B
  TrackPage ──POST /api/public/{app}/track──> TrackRateLimitInterceptor (429)
                                              └─> TrackController ──> TrackingService
                                                    ├─> PublishedDefinitions.latestPublished(app)   (tracking config, statuses)
                                                    ├─> PublishedDefinitions.publishedVersion(app, instance.version) (status colour)
                                                    └─> TrackingRepository (JdbcClient) ──> record, workflow_instance, history_entry
```

### Why these choices

| Decision | Choice | Alternatives and why not |
|---|---|---|
| Admin role check | `AdminGuard.require(RecordController.caller())` in the controller, like `DefinitionController`. | `@PreAuthorize`: the steering says authorisation is checked in code against configuration, and the admin role is configurable. |
| User read model | A separate `UserRow` (id, username, displayName, active, roles, createdAt) for admin reads. `UserAccount` (has the hash) is not used by the admin API. | Reusing `UserAccount` and blanking the hash: one forgotten mapping leaks it. A type without the hash cannot leak it (Property 5). |
| Rate limiter location | Spring `HandlerInterceptor` for `/api/public/*/track`, throwing `TooManyRequests`, handled in `ApiExceptionHandler`. | Servlet filter: would need its own JSON writer. Bucket4j/Resilience4j: a new dependency for ten lines of logic. Nginx `limit_req`: not testable in the Java suite, and bypassed when the backend port is reachable. |
| Limiter algorithm | Sliding-window log per IP (a deque of at most `limit` timestamps). | Fixed window: allows 2x the limit across a window edge. |
| Tracking config source | Latest PUBLISHED definition. Status colour and category come from the definition version the instance is pinned to. | Pinned version for tracking: authors could not switch tracking off. Latest version for colours: a renamed or removed status would lose its colour. |
| Email comparison | In SQL: `lower(btrim(data ->> :field)) = :email`. | Load the data and compare in Java: puts the contact value in application memory and makes the 404 paths differ. |
| Same 404 | One `NotFound("No matching request found")` thrown from one place at the end of the service. | Separate messages per cause: lets a caller enumerate references. |
| Roles in JWT | Left alone (stateless). `/me` already checks the user is active. | Token denylist or per-request user lookup: extra DB read on every request, outside the asked scope. Mentioned as a limitation in Requirement 5.5. |

## Components and Interfaces

### Backend: Part A

**`web/UserAdminController`** (`@RequestMapping("/api/admin/users")`, every method starts with `admin.require(RecordController.caller())`):

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /` `?q&role&active&page&size&sort` | | 200 `UserPage` | 400, 401, 403 |
| `GET /role-options` | | 200 `RoleOption[]` | 401, 403 |
| `GET /{id}` | | 200 `UserView` | 401, 403, 404 |
| `POST /` | `CreateUserRequest` | 201 `UserView` + `Location` | 401, 403, 409 `USERNAME_TAKEN`, 422 |
| `PATCH /{id}` | `UpdateUserRequest` | 200 `UserView` | 401, 403, 404, 409 `SELF_LOCKOUT`, 422 |
| `POST /{id}/password` | `{ password }` | 204 | 401, 403, 404, 422 |

`/role-options` is declared before `/{id}`; the `{id}` variable is a `UUID`, so `role-options` could never match it anyway (a non-UUID id gives 400 through the existing handler for type mismatch; verify in the web test).

DTOs (records in `web`, with springdoc `@Schema`):

```java
record UserView(UUID id, String username, String displayName, boolean active, List<String> roles, Instant createdAt) {}
record UserPage(List<UserView> items, int page, int size, long total, String sort) {}
record RoleOption(String code, String label, List<String> apps) {}
record CreateUserRequest(String username, String displayName, String password, List<String> roles, Boolean active) {}
// PATCH: Optional-like presence is needed, so the controller binds a JsonNode/Map and UserAdminService reads presence
record UpdateUserRequest(...) // see "Partial update binding"
```

**Partial update binding.** PATCH needs "absent" to differ from "null". The controller binds `Map<String,Object>` (the body is small and flat), and `UserAdminService.update` rejects unknown keys (including `username`) with `ValidationFailed` violations (`rule: UNKNOWN_PROPERTY`), then reads `displayName`, `roles`, `active` by key. Types are checked (`roles` must be an array of strings, `active` a boolean) and reported as violations.

**`app/UserAdminService`** (`@Service`, one transaction per public method):

```java
UserPageResult search(UserSearch q);               // q, role, active, page, size, sort
UserRow get(UUID id);
UserRow create(CreateUser cmd, Principal actor);
UserRow update(UUID id, Map<String,Object> patch, Principal actor);
void resetPassword(UUID id, String password, Principal actor);
List<RoleOption> roleOptions();
```

`create`: validate fields (collect all violations, 422) -> `assignableRoles()` check -> `encoder.encode` -> `users.insert` (catches `DuplicateKeyException` and maps to `Conflict("USERNAME_TAKEN", ...)`; a pre-check `findByUsername` gives the same error without a DB error in the common case) -> audit `USER_CREATED`.

`update`: load user (404) -> unknown keys -> field validation -> role check against `assignableRoles() ∪ currentRoles` -> self-lockout check when `id == actor.userId()` (`active == false`, or `roles` given and the admin role in the current roles but not in the new set) -> single `users.update(id, displayName?, active?, roles?)` -> audit `USER_UPDATED` with the changed property names.

Self-lockout is checked after validation, so a request that is both invalid and a lockout reports the 422 first. The lockout check runs before any write, so nothing of the request is applied.

`RoleOptionsService.assignable()`:

```
for app in DefinitionQueryService.appCodes():
    latestPublished(app).ifPresent(p -> for role in p.definition().roles() if !role.isPublic(): merge(code -> apps, label))
add admin role -> { label: "Platform administrator", apps: [] }
sort by code; apps sorted; label from the smallest app code
```

A code that is public in one app and non-public in another is included because the non-public app contributes it (Requirement 6.6).

**`persistence/UserRepository`** gains (and `InMemoryUserRepository` in the test sources implements them):

```java
record UserRow(UUID id, String username, String displayName, boolean active, Set<String> roles, Instant createdAt) {}
record UserSearch(String q, String role, Boolean active, int page, int size, UserSort sort) {}
record UserSort(UserSortField field, boolean asc) {}      // enum: USERNAME, DISPLAY_NAME, ACTIVE, CREATED_AT
Page<UserRow> search(UserSearch s);
Optional<UserRow> findRow(UUID id);
void update(UUID id, String displayName, Boolean active, Set<String> roles);   // null = leave unchanged
void updatePasswordHash(UUID id, String hash);
```

SQL (JdbcClient, named parameters; the sort column comes from a closed enum, never from the request string):

```sql
-- search; :q is already escaped (\, %, _ prefixed with \) and wrapped as %q%
SELECT u.id, u.username, u.display_name, u.active, u.created_at
FROM app_user u
WHERE (:q IS NULL OR u.username ILIKE :q ESCAPE '\' OR u.display_name ILIKE :q ESCAPE '\')
  AND (:role IS NULL OR EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role_code = :role))
  AND (:active IS NULL OR u.active = :active)
ORDER BY <enum column> <ASC|DESC>, u.id
LIMIT :size OFFSET :offset;

-- count: the same WHERE, SELECT count(*)
-- roles for the page, one query:  SELECT user_id, role_code FROM user_role WHERE user_id IN (:ids)
-- update
UPDATE app_user SET display_name = COALESCE(:display, display_name), active = COALESCE(:active, active) WHERE id = :id;
DELETE FROM user_role WHERE user_id = :id;            -- only when roles are given
INSERT INTO user_role (user_id, role_code) VALUES (:id, :role);   -- per role
-- password
UPDATE app_user SET password_hash = :hash WHERE id = :id;
```

`:q IS NULL` with a typed null needs `CAST(:q AS text)`; the Testcontainers test pins this. `display_name` may be NULL in old rows; `ILIKE` on NULL is not true, which is the wanted result.

### Backend: Part B

**Model and schema.** `Entity` becomes `Entity(code, label, titleField, referencePrefix, List<Field> fields, Tracking tracking)` with a 5-argument constructor that passes `null`, so existing call sites compile unchanged. `record Tracking(String contactField)`. `DefinitionParser` reads `tracking.contactField`; the definition read projections keep it for the restricted view (it is not in the Public_Subset).

Schema addition on `$defs.entity.properties`:

```json
"tracking": {
  "type": "object",
  "required": ["contactField"],
  "additionalProperties": false,
  "properties": { "contactField": { "$ref": "#/$defs/fieldCode" } },
  "description": "Enables public status tracking for records of this entity. The citizen supplies the reference number and the value of this EMAIL field."
}
```

**Validator.** New `domain/validator/TrackingRules implements RuleGroup`, registered where the other groups are. For every entity with `tracking`: `BAD_TRACKING_FIELD` (path `entities[X].tracking.contactField`), `TRACKING_FIELD_NOT_EMAIL`, `TRACKING_NEEDS_REFERENCE` (entity has no `referencePrefix`). All violations are reported, as in the other groups.

**`web/TrackController`**: `POST /api/public/{app}/track` takes `TrackRequest(String reference, String email)`, calls `TrackingService.track`, returns `TrackResponse`, sets `Cache-Control: no-store`. `SecurityConfig` adds `.requestMatchers(HttpMethod.POST, "/api/public/*/track").permitAll()`.

```java
record TrackRequest(String reference, String email) {}
record TrackResponse(String reference, String title, StatusView status, Instant createdAt, Instant updatedAt,
                     List<TimelineEntry> timeline) {}
record StatusView(String label, String color, String category) {}
record TimelineEntry(Instant at, String status) {}
```

The response is built only from these records, so Jackson cannot emit anything else. A test checks the serialised key set (Property 8).

**`app/TrackingService.track(app, reference, email)`**

```
1. validate lengths / blank -> ValidationFailed (422)                      // no data access yet
2. ref = reference.trim().toUpperCase(Locale.ROOT); mail = email.trim().toLowerCase(Locale.ROOT)
3. published = definitions.latestPublished(app)
4. cand = trackingRepo.findCandidate(app, ref)                             // id and entityCode only
5. entity = published.definition().entity(cand.entityCode()); tracking = entity.tracking()
6. any of 3,4,5 empty / tracking null  -> throw NO_MATCH
7. ok = trackingRepo.contactMatches(cand.id(), tracking.contactField(), mail)
8. !ok -> throw NO_MATCH
9. snap = trackingRepo.snapshot(cand.id())      // record created_at, title value, instance id/version/workflow/status/updated_at; empty -> NO_MATCH
10. wf = definitions.publishedVersion(app, snap.version()).flatMap(workflow(snap.workflowCode()))
11. status = wf.statuses() by label -> color, category; if the label is not found (e.g. END outcome label is declared, so normally found) -> category null, color null
12. timeline = trackingRepo.timeline(snap.instanceId())
13. return TrackResponse
```

`NO_MATCH` is `new NotFound("No matching request found")`, created by one private static method, so no path can word it differently.

Note on step 12 and 3: `publishedVersion(app, version)` is used if the pinned version is still published (published versions are immutable, so it always is). If the lookup returns empty (corrupt data), the status has label from the instance and null color/category; the response is still 200 because the match was proven.

**`persistence/TrackingRepository`** (JdbcClient):

```sql
-- findCandidate
SELECT id, entity_code FROM record WHERE app_code = :app AND reference_no = :ref;

-- contactMatches: :field is bound as a parameter of the ->> operator; the name is a validated fieldCode
SELECT EXISTS (SELECT 1 FROM record
               WHERE id = :id AND lower(btrim(data ->> :field)) = :email);

-- snapshot
SELECT r.created_at, r.data ->> :titleField AS title,
       i.id AS instance_id, i.version, i.workflow_code, i.status, i.updated_at
FROM record r JOIN workflow_instance i ON i.record_id = r.id
WHERE r.id = :id;

-- timeline: whitelist of kinds, only real status changes
SELECT event_time, to_status
FROM history_entry
WHERE instance_id = :iid
  AND to_status IS NOT NULL
  AND (kind = 'START' OR (kind = 'ACTION' AND to_status IS DISTINCT FROM from_status))
ORDER BY event_time, seq;
```

The email value is compared with `=`, so `%` and `_` in it are ordinary characters. The reference is compared with `=` as well. The `titleField` may be null (entity without one): the service passes `null` and the title is `null`. The title is cut to 200 characters.

A `START` row has `from_status = null` in the router; the `kind = 'START'` branch covers it either way. Consecutive identical statuses cannot appear because of the `DISTINCT FROM` rule on `ACTION` rows.

**`web/TrackRateLimitInterceptor`** and **`config/TrackRateLimiter`**

```java
final class TrackRateLimiter {
    TrackRateLimiter(int perMinute, int maxKeys, Clock clock)
    synchronized Decision tryAcquire(String key)   // Decision(boolean allowed, long retryAfterSeconds)
}
```

State: access-ordered `LinkedHashMap<String, ArrayDeque<Long>>` capped at `maxKeys` (`removeEldestEntry`). `tryAcquire` drops timestamps older than 60 000 ms, allows if size < limit and records the time, otherwise denies with `retryAfter = ceil((oldest + 60000 - now) / 1000)`, at least 1. Denied requests are not recorded (a client that keeps retrying is let in again exactly 60 s after its first counted request). A single `synchronized` method is enough for the expected traffic.

Requirement 10.1 says "every request counts, whatever the outcome": allowed requests are recorded whether they end in 200, 404 or 422. Denied requests are not recorded, which keeps the deque bounded by `limit`.

Client IP: `ClientIp.resolve(HttpServletRequest, String headerName)`: if `headerName` is non-blank and the header has a value that parses as an IP literal (no DNS lookup; use a small regex-free check with `InetAddresses`-style parsing of IPv4/IPv6 literals), use it, else `request.getRemoteAddr()`. Only the first comma-separated element is read.

Trust note: the header is only trustworthy when the backend is reachable only through the reverse proxy, as in `docker-compose.yml`. `nginx.conf` already sets `X-Real-IP $remote_addr`, overwriting any client-supplied value. This is documented next to the property in `application.properties`.

Configuration (`application.properties`, bound in `SkeletonProperties`):

```properties
skeletonkey.track.rate-limit.per-minute=10
skeletonkey.track.rate-limit.max-clients=10000
# Header carrying the real client address when behind the reverse proxy. Blank = use the socket address.
skeletonkey.track.client-ip-header=X-Real-IP
```

Properties are validated at startup (per-minute >= 1, max-clients >= 100), like the admin role.

`ApiExceptionHandler` gets `@ExceptionHandler(TooManyRequests.class)` -> 429 `RATE_LIMITED`, with `Retry-After`.

**Public_Subset.** `DefinitionReadService` adds `trackingEnabled: true` to a public form's object when `entity(form.entity()).tracking() != null`. The existing allow-list test is extended with the new key.

### Frontend

Pure logic is kept in plain TypeScript files so it can be property-tested without TestBed.

```
core/api/api.types.ts            + UserView, UserPage, RoleOption, CreateUserRequest, UpdateUserRequest, TrackRequest, TrackResponse
core/api/api.service.ts          + listUsers, getUser, createUser, updateUser, resetPassword, roleOptions, track
features/admin/admin.routes.ts   + { path: 'users', loadComponent: users-page }
features/admin/admin-shell.component.ts   + <a nz-button routerLink="/admin/users">Users</a>
features/admin/users/
  users-page.component.ts        table, filters, paging, row actions        (OnPush, signals)
  user-drawer.component.ts       create/edit form                            (NzDrawer + reactive form)
  reset-password-modal.component.ts
  user-form-model.ts             pure: validate(form) -> errors; toCreateRequest(); toPatch(original, edited) -> only changed
features/public-track/
  track.page.ts                  form + result card + timeline + errors
  track-model.ts                 pure: validateTrackForm(), statusChipColor(status), toTimelineItems(response)
app.routes.ts                    + { path: 'public/:app/track', ... } BEFORE 'public/:app/:form'
features/public-form/public-form.page.ts   success view: link when form.trackingEnabled
```

- `track-model.statusChipColor`: use `status.color` when it is a `#rrggbb` string, otherwise a per-category default (`OPEN` blue, `IN_PROGRESS` gold, `DONE` green, `REJECTED` red), taken from NG-ZORRO preset colour names.
- `toPatch(original, edited)` returns only the properties that differ; roles are compared as sets. If nothing differs, Save closes the drawer without a request.
- The admin's own row: `SessionService` exposes the user id (from `/me`). The switch and the admin role option are disabled when `row.id === session.userId`.
- Error mapping uses the existing `api-error.ts` helpers; 409 `USERNAME_TAKEN`, 409 `SELF_LOCKOUT`, 422 violations, 404 and 429 (with `Retry-After`) each have a distinct handler in the component.
- The password in the create drawer and the reset modal lives only in the form control; the control is reset and the drawer destroyed on close.

## Data Models

No table changes. Shapes:

```jsonc
// GET /api/admin/users
{ "items": [ { "id": "…", "username": "asha", "displayName": "Asha Rai", "active": true,
               "roles": ["OFFICER"], "createdAt": "2026-11-01T08:00:00Z" } ],
  "page": 0, "size": 20, "total": 1, "sort": "username,asc" }

// GET /api/admin/users/role-options
[ { "code": "OFFICER", "label": "Officer", "apps": ["GRIEVANCE"] },
  { "code": "PLATFORM_ADMIN", "label": "Platform administrator", "apps": [] } ]

// POST /api/public/GRIEVANCE/track  { "reference": "GRV-2026-000123", "email": "a@b.org" }
{ "reference": "GRV-2026-000123", "title": "Broken street light",
  "status": { "label": "In review", "color": "#faad14", "category": "IN_PROGRESS" },
  "createdAt": "2026-11-02T09:10:00Z", "updatedAt": "2026-11-04T14:22:00Z",
  "timeline": [ { "at": "2026-11-02T09:10:00Z", "status": "Received" },
                { "at": "2026-11-04T14:22:00Z", "status": "In review" } ] }

// 404 and 429 use the ApiError shape
{ "status": 404, "code": "NOT_FOUND", "message": "No matching request found", "requestId": "…", "violations": [] }
{ "status": 429, "code": "RATE_LIMITED", "message": "Too many requests", "requestId": "…", "violations": [] }
```

## Error Handling

| Case | Status | Code | Where |
|---|---|---|---|
| No/invalid JWT on admin API | 401 | `UNAUTHENTICATED` | existing entry point |
| Not admin | 403 | `FORBIDDEN` | `AdminGuard` -> `AccessDeniedException` |
| Bad paging/sort parameter | 400 | `BAD_REQUEST` | `BadRequest` |
| Unknown user id | 404 | `NOT_FOUND` | `NotFound` |
| Duplicate username (also under a race) | 409 | `USERNAME_TAKEN` | `Conflict` |
| Self deactivate / self admin removal | 409 | `SELF_LOCKOUT` | `Conflict` |
| Bad field, unknown role, unknown property | 422 | `VALIDATION_FAILED` | `ValidationFailed` with violations |
| Any tracking mismatch | 404 | `NOT_FOUND` (fixed message) | `NotFound` |
| Tracking over the limit | 429 | `RATE_LIMITED` | `TooManyRequests` |
| Unexpected failure | 500 | `INTERNAL_ERROR` | existing handler, no detail |

Password values are never part of an exception message, log line, audit detail or violation message.

## Correctness Properties

Each property is its own test. Backend properties use jqwik (200 tries unless noted); the ones that need PostgreSQL use `@Testcontainers` with a small `tries` (for example 25) and reuse one container.

**Property 1: A mismatched email never returns data.** For any stored record with contact email `e`, any reference `r` and any email `x` such that `lower(trim(x)) != lower(trim(e))`, `track(app, r, x)` throws the 404 and returns no `TrackResponse`. This includes `x` containing `%`, `_`, `.*`, quotes, or the stored email with a character changed. Validates Requirements 8.1, 8.4.

**Property 2: All failure causes look the same.** For the six failure causes of Requirement 8.2, the serialised response (status, headers, body except `requestId`) is byte-identical. Generated over random references, apps and emails. Validates 8.2.

**Property 3: Email matching ignores case and surrounding whitespace.** For a stored email `e` and any case variation and padding of it, `track` with the right reference returns 200, and with a different reference case (`grv-…`) also 200. Validates 8.1, 8.4.

**Property 4: Deactivated users are never assigned.** For any set of users holding role R with arbitrary `active` flags, any open-task counts and any previous round-robin cursor, `AssigneeResolver` with `LEAST_LOADED` or `ROUND_ROBIN` returns only an active user, or `UNASSIGNED` when none is active. A Testcontainers variant runs through `UserAdminService.update(active=false)` and the real `JdbcUserRepository`. Validates 5.2.

**Property 5: The user API never exposes a password hash.** For any created and updated users (random usernames, names, passwords of 8-72 bytes, roles), the JSON of every User_Admin_API response, and the serialised `UserView` / `UserPage`, contains no property named like `password`/`hash` and no value that equals the stored hash or starts with `$2`. Also a reflection check that `UserRow` and `UserView` have no such component. Validates 2.10.

**Property 6: Role validation matches the role options.** For any set of role codes `S` and any set of published definitions, `create` succeeds iff `S ⊆ Assignable_Roles`; otherwise it returns 422 `UNKNOWN_ROLE` naming exactly the codes in `S \ Assignable_Roles`. Public-role codes alone are rejected. For `update`, codes already held are also accepted. Validates 3.6, 4.3, 6.2 to 6.6.

**Property 7: Admins cannot lock themselves out.** For any admin actor and any PATCH to their own id: the call fails with `SELF_LOCKOUT` and leaves the stored user unchanged iff the patch sets `active=false` or sets a role set without the admin role; otherwise it succeeds. PATCHes to other users never raise `SELF_LOCKOUT`. Validates 5.4.

**Property 8: The track response contains only public-safe data.** For records with random remarks, actors, assignees, history kinds (including `SKIPPED`, `EVENT_FAILED`) and random extra fields, the serialised response has exactly the keys `reference,title,status,createdAt,updatedAt,timeline` (status: `label,color,category`; entries: `at,status`), the timeline equals the status changes of `START`/`ACTION` rows in order, and the serialised text contains none of the generated remarks, actor names, assignee names, step codes or the contact email. Validates 8.7 to 8.9.

**Property 9: The rate limiter allows exactly `limit` requests per window per address.** For any limit `n`, any sequence of (address, time) events: per address, an event is allowed iff fewer than `n` allowed events of that address fall in the previous 60 s; a denied event never changes the state; addresses do not affect each other; `retryAfter >= 1` and the request is allowed again after `retryAfter` seconds; the number of tracked addresses never exceeds `maxKeys`. Compared against a simple reference model. Validates 10.1, 10.3, 10.5, 10.6.

**Property 10: The validator accepts a tracking config iff it is valid.** For generated definitions with a `tracking` block on an entity, the Validator reports `BAD_TRACKING_FIELD` iff the field is missing, `TRACKING_FIELD_NOT_EMAIL` iff it exists with a non-EMAIL type, `TRACKING_NEEDS_REFERENCE` iff the entity has no prefix, and nothing otherwise; unrelated errors are still all reported. Uses `DefinitionArbitraries`. Validates 9.3.

**Property 11: `tracking` survives load and projection.** For generated definitions with and without `tracking`, parse -> model -> serialise gives an equal `tracking` value; the restricted definition keeps it; the Public_Subset never contains `contactField` and sets `trackingEnabled` exactly on public forms of tracked entities. Validates 9.1, 9.2, 9.5.

**Property 12: User creation rules.** For any usernames and passwords: a second create with an equal username (any other values) gives 409 `USERNAME_TAKEN` and leaves one user; a password under 8 or over 72 bytes gives 422 and creates nothing; a valid password gives a stored hash for which `encoder.matches(password, hash)` is true and the hash differs from the password. Validates 3.2 to 3.5, 4.6.

**Property 13 (frontend, fast-check): Track form validity.** `validateTrackForm(reference, email)` is valid iff the trimmed reference is non-empty and at most 128 characters and the email is a syntactically valid address of at most 254 characters; invalid input never reaches the API call. Validates 11.2.

**Property 14 (frontend, fast-check): Timeline and chip rendering.** For any `TrackResponse`, `toTimelineItems` keeps the order and the count of entries and exposes only `at` and `status`; `statusChipColor` returns the given colour when it is a `#rrggbb` string, else a colour from the category map, and never throws. For any `UserView` edit, `toPatch` is empty iff nothing changed, and applying it to the original gives the edited values. Validates 7.4, 11.4.

## Testing Strategy

- **Unit and web tests (MockMvc, in-memory repositories already used by `AuthSecurityTest`)**: 401/403/404/409/422 mapping for every endpoint; admin gate on every method; `Retry-After` header; `Cache-Control: no-store`; `/api/public/*/track` works without a JWT and ignores a bad one; login of a deactivated user is indistinguishable from a wrong password.
- **Testcontainers (PostgreSQL 16, Flyway applied)**: `JdbcUserRepositoryAdminTest` (search, `ILIKE` escaping of `%`/`_`/`\`, role and active filters, stable paging and sorting, role replacement is atomic, duplicate username -> `DuplicateKeyException`), `JdbcTrackingRepositoryTest` (candidate, `contactMatches` with mixed case/whitespace/wildcards, snapshot, timeline filtering over every history kind), and `DeactivationAssignmentIT` (deactivate through the service; start workflows with `LEAST_LOADED` and `ROUND_ROBIN`; the deactivated user gets nothing; their existing task still appears in a supervisor's `MY_ROLE_QUEUE` view and the instance view shows their name).
- **Property tests**: Properties 1 to 12 as above.
- **Frontend**: component specs for the users page (table render, search debounce, create drawer, active toggle with confirm and rollback, reset modal, error mapping), the track page (form, 200, 404, 429 with countdown, prefill from `?reference=`), and the public form success link; fast-check for Properties 13 and 14.
- **Manual smoke** (`docker compose up`, Windows CMD): create a user, log in as them, deactivate, confirm login fails; submit the public grievance form, follow the "Track this request" link, check status; send 11 track requests in a minute and see 429.

## Open points for the author to confirm

1. JWTs issued before a deactivation or role change stay valid until they expire (Requirement 5.5). If this is not acceptable, the next step is a per-request `active` check in `JwtAuthFilter`, which costs one indexed lookup per request.
2. Usernames are unique case-sensitively (the existing constraint). `Asha` and `asha` can both exist. A case-insensitive unique index would be `V3__username_ci.sql` and could fail on existing data.
3. Rate limiting is per backend instance, in memory. With several backend replicas the effective limit multiplies.
