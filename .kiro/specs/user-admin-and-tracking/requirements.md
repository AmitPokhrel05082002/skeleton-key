# Requirements Document

## Introduction

This feature adds two independent capabilities to the Skeleton (`skeleton/backend`, Java package `dev.skeletonkey`; `skeleton/frontend`, Angular + NG-ZORRO).

- **Part A, User administration.** A Platform_Admin manages users (create, list, edit, activate/deactivate, reset password) through `/api/admin/users` and an `/admin/users` page. Today users exist only through the seed loader.
- **Part B, Citizen status tracking.** A citizen who submitted a public form can look up the current status of the request without logging in, by giving the reference number and the contact email. The app definition decides whether tracking is on and which field is the contact field.

The work is additive. No existing endpoint path or response property changes, except that the Public_Subset gains one boolean on public forms (Requirement 12). One optional schema property (`entity.tracking`) is added. One Flyway migration is added, `V3__username_case_insensitive.sql` (unique index on `lower(username)`). Every other column and constraint needed already exists in `V1__init.sql`. Three existing behaviours change on purpose: usernames become case-insensitive (Requirements 3.3, 3.4, 5.7), the JWT filter rejects tokens of inactive users (Requirement 5.5), and the client IP header is trusted only from trusted proxies (Requirement 10.2). `docker-compose.yml` binds the backend port to loopback (Requirement 10.9).

Conventions that apply (from the steering files): configuration only in `application.properties`; errors only through `ApiExceptionHandler` with the common `ApiError` body (5xx bodies carry no internal detail); layering `web` -> `service` -> `repository`, no business logic in controllers; JdbcClient with named parameters only; `schema/app-definition.schema.json` is the source of truth, so a schema change updates the Java model, the TypeScript types, the publish-time Validator, `examples/` and the steering doc `.kiro/steering/app-definition.md` in the same change; apps never edit `skeleton/`; tests use JUnit 5, Testcontainers for PostgreSQL and jqwik (backend) and fast-check (frontend).

Out of scope: self-registration, email delivery of passwords, password-change by the user themselves, token denylists or refresh tokens, per-field tracking visibility, CAPTCHA, a distributed rate limiter, and any change to the Router, the Validator rules not named here, or the SLA scheduler.

## Glossary

- **Skeleton_Backend**: The Spring Boot application in `skeleton/backend`.
- **Skeleton_Frontend**: The Angular application in `skeleton/frontend`.
- **Platform_Admin**: A user holding the role named by `skeletonkey.admin-role` (default `PLATFORM_ADMIN`), checked with `AdminGuard`.
- **Principal**: The authenticated caller (user id and role codes) of a request.
- **User**: A row of `app_user` with its role codes in `user_role`: `id`, `username`, `displayName`, `active`, `roles[]`, `createdAt`.
- **User_Admin_API**: The endpoints under `/api/admin/users`.
- **User_View**: The JSON shape the User_Admin_API returns for one user: `id`, `username`, `displayName`, `active`, `roles`, `createdAt`. It never contains a password or password hash.
- **Role_Option**: One entry `{ code, label, apps[] }` of the role picker list. `apps` is empty for the platform admin role.
- **Assignable_Roles**: The set of role codes a user may hold: the union of the non-public role codes of the latest PUBLISHED definition of every app, plus the admin role.
- **Open_Task**: A workflow instance with `ended = false` whose `assignee` is a given user.
- **Reference_No**: The human-readable record number (`record.reference_no`), for example `GRV-2026-000123`.
- **Tracking_Config**: The optional entity property `tracking` with the single property `contactField`.
- **Contact_Field**: The entity field named by `tracking.contactField`. It has type `EMAIL`.
- **Track_Endpoint**: `POST /api/public/{app}/track`.
- **Track_Request**: The Track_Endpoint body `{ reference, email }`.
- **Track_Response**: The Track_Endpoint 200 body (Requirement 8).
- **Public_Timeline**: The ordered list of `{ at, status }` entries in the Track_Response.
- **Public_Subset**: The anonymous projection returned by `GET /api/public/apps/{app}`.
- **Rate_Limiter**: The per-client-IP limiter in front of the Track_Endpoint.
- **Client_IP**: The address the Rate_Limiter keys on (Requirement 10.2).
- **Trusted_Proxy**: A direct peer address inside `skeletonkey.trusted-proxies`; only such a peer may supply the client address in `X-Real-IP`.
- **Active_Cache**: The per-instance cache (at most 30 s) of the `active` flag used by `JwtAuthFilter` (Requirement 5.5).
- **Common_Error_Body**: The `ApiError` JSON (`status`, `code`, `message`, `requestId`, `violations[]`) returned by `ApiExceptionHandler`.
- **Validator**: The publish-time component that checks cross-reference rules of an app definition.
- **Admin_Area**: The lazy-loaded `/admin` route tree guarded by `authGuard` and `adminGuard`.

## Requirements

### Part A: User administration

### Requirement 1: Access control for the User Admin API

**User Story:** As a platform owner, I want only platform admins to manage users, so that nobody can grant themselves access.

#### Acceptance Criteria

1. THE Skeleton_Backend SHALL serve every endpoint under `/api/admin/users` only to a Principal holding the admin role, using `AdminGuard.require`.
2. IF a request to the User_Admin_API carries no valid JWT, THEN THE Skeleton_Backend SHALL respond with HTTP status 401 and the Common_Error_Body.
3. IF the Principal does not hold the admin role, THEN THE Skeleton_Backend SHALL respond with HTTP status 403 and the Common_Error_Body, and SHALL NOT read or change any user.
4. THE Skeleton_Backend SHALL NOT add `/api/admin/**` to the permit-all list of `SecurityConfig`.

### Requirement 2: List and read users

**User Story:** As a platform admin, I want to find users quickly, so that I can change the right account.

#### Acceptance Criteria

1. WHEN a Platform_Admin sends `GET /api/admin/users`, THE Skeleton_Backend SHALL respond with HTTP status 200 and a page `{ items[], page, size, total, sort }` of User_Views, with the same envelope as `RecordPage`.
2. THE Skeleton_Backend SHALL accept the query parameters `page` (0-based, default 0), `size` (default 20, capped at `skeletonkey.page.max-size`) and `sort` (`field` or `field,asc|desc`, where field is one of `username`, `displayName`, `active`, `createdAt`; default `username,asc`).
3. IF `sort` names a field outside that list, or `page`/`size` is not a valid non-negative/positive integer, THEN THE Skeleton_Backend SHALL respond with HTTP status 400 and the Common_Error_Body.
4. WHERE the query parameter `q` is present and non-blank, THE Skeleton_Backend SHALL return only users whose `username` or `displayName` contains `q` as a case-insensitive substring. THE Skeleton_Backend SHALL treat `%`, `_` and `\` in `q` as literal characters.
5. WHERE the query parameter `role` is present, THE Skeleton_Backend SHALL return only users holding that role code.
6. WHERE the query parameter `active` is `true` or `false`, THE Skeleton_Backend SHALL return only users with that `active` value.
7. THE Skeleton_Backend SHALL apply all given filters together (logical AND) and SHALL compute `total` over the filtered set.
8. THE Skeleton_Backend SHALL order results by the sort field and then by `id`, so paging is stable.
9. WHEN a Platform_Admin sends `GET /api/admin/users/{id}`, THE Skeleton_Backend SHALL respond with 200 and the User_View, or with 404 and the Common_Error_Body when no user has that id.
10. THE Skeleton_Backend SHALL return in every User_Admin_API response only User_View properties, and SHALL NOT return the password, the password hash or any value starting with a bcrypt prefix (`$2`), in any response or log line of this feature.

### Requirement 3: Create a user

**User Story:** As a platform admin, I want to create an account with an initial password and roles, so that a new colleague can log in.

#### Acceptance Criteria

1. WHEN a Platform_Admin sends `POST /api/admin/users` with `{ username, displayName, password, roles[], active }`, THE Skeleton_Backend SHALL create the user and respond with HTTP status 201, the User_View and a `Location` header for `/api/admin/users/{id}`.
2. THE Skeleton_Backend SHALL require `username` to be 3 to 64 characters after trimming, with no whitespace, `displayName` to be 1 to 120 characters after trimming, and `password` to be 8 to 72 bytes in UTF-8 (72 is the bcrypt input limit). `roles` SHALL default to an empty set and `active` SHALL default to `true`.
3. THE Skeleton_Backend SHALL store the username as trimmed, with its case kept for display, and SHALL treat usernames as case-insensitive everywhere: uniqueness, login and every lookup compare `lower(username)`. The migration `V3__username_case_insensitive.sql` SHALL add a unique index on `lower(username)`. THE Skeleton_Backend SHALL store the password only as the hash produced by the existing `PasswordEncoder` bean.
4. IF a user whose username equals the new `username` ignoring case (`Asha` and `asha`) already exists, THEN THE Skeleton_Backend SHALL respond with HTTP status 409, code `USERNAME_TAKEN` and the Common_Error_Body, and SHALL create nothing. This SHALL also hold when two requests with the same username arrive concurrently (the unique constraint decides; the loser gets 409, never 500).
5. IF any field breaks a rule of criterion 2, THEN THE Skeleton_Backend SHALL respond with HTTP status 422, code `VALIDATION_FAILED` and one violation per bad field (`field`, `rule`, `message`), and SHALL create nothing. Violation messages SHALL NOT echo the password.
6. IF `roles` contains a code outside Assignable_Roles, THEN THE Skeleton_Backend SHALL respond with HTTP status 422 with a violation for field `roles`, rule `UNKNOWN_ROLE`, naming every unknown code, and SHALL create nothing.
7. THE Skeleton_Backend SHALL create the user and its role rows in one transaction.
8. THE Skeleton_Backend SHALL write an audit entry through `AuditService` for the creation (actor, target user id, username; never the password).

### Requirement 4: Update a user and reset a password

**User Story:** As a platform admin, I want to rename a user, change their roles, switch them off and reset their password, so that accounts follow staff changes.

#### Acceptance Criteria

1. WHEN a Platform_Admin sends `PATCH /api/admin/users/{id}` with any of `displayName`, `roles[]`, `active`, THE Skeleton_Backend SHALL change only the properties present and respond with 200 and the updated User_View. A property that is absent SHALL stay unchanged.
2. THE Skeleton_Backend SHALL NOT allow `username` to change. IF the body contains `username` or any unknown property, THEN THE Skeleton_Backend SHALL respond with HTTP status 422 and a violation naming the property.
3. THE Skeleton_Backend SHALL apply the `displayName` rule of Requirement 3.2 and the `UNKNOWN_ROLE` rule of Requirement 3.6 to the properties present, with one exception: a role code that the user already holds SHALL be accepted even if it is no longer in Assignable_Roles, so an admin can edit other properties of a user whose app role was removed from a newer definition.
4. THE Skeleton_Backend SHALL replace the role set as a whole (the `roles` array in the body is the new set) in one transaction with the other changes of the same request.
5. IF no user has the id, THEN THE Skeleton_Backend SHALL respond with HTTP status 404 and the Common_Error_Body.
6. WHEN a Platform_Admin sends `POST /api/admin/users/{id}/password` with `{ password }`, THE Skeleton_Backend SHALL apply the password rule of Requirement 3.2, replace the stored hash with a new hash from the `PasswordEncoder`, and respond with HTTP status 204 and no body.
7. THE Skeleton_Backend SHALL write an audit entry for each update and each password reset (actor, target, names of the changed properties; never the password or hash).
8. THE Skeleton_Backend SHALL treat a PATCH that changes nothing (every value equal to the stored value) as a success with HTTP status 200.

### Requirement 5: Deactivation and self-protection

**User Story:** As a platform owner, I want deactivated users locked out of new work without losing existing tasks, and I want admins unable to lock themselves out by mistake.

#### Acceptance Criteria

1. IF a user with `active = false` sends `POST /api/auth/login` with the correct password, THEN THE Skeleton_Backend SHALL respond exactly as for a wrong password (HTTP status 401, same code and message), so the response does not reveal that the account exists or is inactive. This is the existing behaviour and SHALL be covered by a test.
2. THE Skeleton_Backend SHALL NOT select a user with `active = false` in `LEAST_LOADED` or `ROUND_ROBIN` assignment. Both strategies SHALL continue to read candidates through `UserRepository.findActiveByRole`, and a test SHALL show that a user deactivated through the User_Admin_API is never chosen (Property 4).
3. WHEN a user is deactivated, THE Skeleton_Backend SHALL leave every Open_Task assigned to that user unchanged, and those tasks SHALL stay visible, with the deactivated user's display name as `assigneeName`, to every Principal that could see them before (for example in `ASSIGNED_TO_ME` views of supervisors with the role queue, `MY_ROLE_QUEUE` views and the instance view). A test SHALL show this on PostgreSQL.
4. IF a Platform_Admin sends a PATCH to their own user id that sets `active` to `false`, or that sets `roles` without the admin role while the user currently holds it, THEN THE Skeleton_Backend SHALL respond with HTTP status 409, code `SELF_LOCKOUT` and the Common_Error_Body, and SHALL change nothing, including the other properties of the same request.
5. WHEN `JwtAuthFilter` has validated a token, THE Skeleton_Backend SHALL check that the user named by `sub` exists and has `active = true`, using a single primary-key lookup (`SELECT active FROM app_user WHERE id = :id`). IF the user is missing or inactive, THEN THE filter SHALL leave the request unauthenticated, so a protected route answers 401 `UNAUTHENTICATED` with the Common_Error_Body. THE check SHALL be answered from a per-instance cache of at most 30 seconds (property `skeletonkey.auth.active-cache-seconds`, default 30, 0 turns the cache off) holding at most 10 000 entries. WHEN `UserAdminService` changes `active`, it SHALL evict that user's cache entry, so on the instance that handled the change the next request of that user is rejected at once; on other instances the delay is at most the cache time. Roles in the token are still taken from the token until it expires (out of scope).
6. THE Skeleton_Frontend SHALL show in the deactivation confirmation: "This user is signed out on their next request."
7. THE Skeleton_Backend SHALL accept login with any casing of the username (`ASHA`, `asha`, `Asha` all log in the user created as `Asha`), with the same timing and error behaviour as before for unknown users.
6. WHEN a user is reactivated, THE Skeleton_Backend SHALL make the user eligible again for assignment and login without any other change.

### Requirement 6: Role options

**User Story:** As a platform admin, I want to pick roles from a list of valid ones, so that I cannot type a role that no app uses.

#### Acceptance Criteria

1. WHEN a Platform_Admin sends `GET /api/admin/users/role-options`, THE Skeleton_Backend SHALL respond with 200 and an array of Role_Options ordered by `code`.
2. THE Skeleton_Backend SHALL build the array from the latest PUBLISHED definition of every app: each role with `public` not true becomes one entry; the same code in several apps becomes one entry whose `apps` lists every app code (sorted) and whose `label` is the label from the app with the smallest app code.
3. THE Skeleton_Backend SHALL add one entry for the admin role with label `Platform administrator` and empty `apps`, even when no app is published.
4. THE Skeleton_Backend SHALL NOT include a role with `public: true`, and SHALL NOT include roles that exist only in DRAFT versions or in older PUBLISHED versions.
5. THE Skeleton_Backend SHALL use this same set (Assignable_Roles) to validate roles in Requirements 3.6 and 4.3, so the picker and the validation cannot disagree.
6. IF a public role code equals the code of a non-public role in another app, THEN THE Skeleton_Backend SHALL keep the code in Assignable_Roles (the non-public role makes it assignable).

### Requirement 7: User administration page

**User Story:** As a platform admin, I want a page to manage users, so that I do not need API calls.

#### Acceptance Criteria

1. THE Skeleton_Frontend SHALL add the lazy route `/admin/users` as a child of the Admin_Area (in `admin.routes.ts`, using `loadComponent`), and SHALL add a link "Users" to the Admin_Area header in `AdminShellComponent`, next to the existing links.
2. THE Skeleton_Frontend SHALL show the users in an NG-ZORRO table with the columns display name, username, roles (as tags), active (a switch) and created date, with server-side paging and sorting, a search box (debounced 300 ms, sent as `q`), a role filter (from the role options) and an active filter (all / active / inactive).
3. WHEN the admin chooses "New user", THE Skeleton_Frontend SHALL open an NG-ZORRO drawer with a form for username, display name, initial password (with the 8-character minimum shown and a show/hide toggle), roles (a multi-select over the role options, showing the code and label) and active. THE Skeleton_Frontend SHALL disable Save until the form is valid under the rules of Requirement 3.2.
4. WHEN the admin chooses Edit on a row, THE Skeleton_Frontend SHALL open the same drawer with the username read-only and no password field, and SHALL send only the properties the admin changed.
5. WHEN the admin switches the Active toggle in a row, THE Skeleton_Frontend SHALL ask for confirmation when deactivating (with the hint of Requirement 5.6), send a PATCH with `active`, and on failure set the switch back and show the API error message.
6. THE Skeleton_Frontend SHALL offer "Reset password" in the drawer and in the row actions. It SHALL open a modal with one password field and SHALL call `POST /api/admin/users/{id}/password`. The password SHALL NOT be stored in any signal, service or log after the call finishes.
7. WHEN the API responds 409 `USERNAME_TAKEN`, THE Skeleton_Frontend SHALL show the error on the username field. WHEN it responds 422 with violations, THE Skeleton_Frontend SHALL show each violation on its field. WHEN it responds 409 `SELF_LOCKOUT`, THE Skeleton_Frontend SHALL show the message in the drawer.
8. THE Skeleton_Frontend SHALL disable the Active switch and the admin role option for the row of the signed-in user and show a tooltip explaining why. The backend rule of Requirement 5.4 stays the authority.
9. THE Skeleton_Frontend SHALL use NG-ZORRO components only, standalone components and signals, and SHALL NOT hard-code role codes.

### Part B: Citizen status tracking

### Requirement 8: Track endpoint

**User Story:** As a citizen, I want to look up my request with the reference and my email, so that I can see its progress without an account.

#### Acceptance Criteria

1. WHEN `POST /api/public/{app}/track` receives `{ reference, email }`, THE Skeleton_Backend SHALL respond with HTTP status 200 and the Track_Response only if all of these hold: the app has a PUBLISHED version; a record with that Reference_No exists in that app; the record's entity has a Tracking_Config in the latest PUBLISHED version of the app (so an author can switch tracking off for every record by publishing a new version); the record has a workflow instance; and the record's Contact_Field value equals `email` ignoring case and surrounding whitespace.
2. IF any condition of criterion 1 fails, THEN THE Skeleton_Backend SHALL respond with HTTP status 404, code `NOT_FOUND`, the fixed message `No matching request found` and the Common_Error_Body. The status, code, message, `violations` and the set of response headers SHALL be identical for "unknown app", "app not published", "tracking not enabled", "no such reference", "reference exists but email differs" and "no workflow instance", so the response cannot be used to find out which references exist.
3. THE Skeleton_Backend SHALL first read only the record id, entity code and version for `(app_code, reference_no)`, then decide the match with a query that compares the trimmed, lower-cased Contact_Field value inside SQL, and SHALL load the title, status and history only after the match is confirmed. The Contact_Field value SHALL never be loaded into the application for comparison.
4. THE Skeleton_Backend SHALL match the reference exactly after trimming and upper-casing it (`grv-2026-000123` finds `GRV-2026-000123`), and SHALL match the email with equality, not with `LIKE` or a pattern, so `%`, `_` and `.*` in the input match nothing special.
5. THE Skeleton_Backend SHALL accept the endpoint without a JWT (permit-all for `POST /api/public/*/track` in `SecurityConfig`), SHALL ignore any `Authorization` header, and SHALL NOT use the caller's roles.
6. IF `reference` or `email` is missing, blank or longer than 128 / 254 characters, or the body is not JSON, THEN THE Skeleton_Backend SHALL respond with HTTP status 422 `VALIDATION_FAILED` (with a violation per field) or 400 `BAD_REQUEST`. These responses carry no information about any record.
7. THE Track_Response SHALL have exactly these properties: `reference`, `title`, `status` `{ label, color, category }`, `createdAt`, `updatedAt`, `timeline[]`. `title` is the value of the entity's `titleField` as text, or `null`. `color` is the `color` of the status declared in the pinned workflow, or `null`. `category` is one of `OPEN`, `IN_PROGRESS`, `DONE`, `REJECTED`. `createdAt` is the record's creation time and `updatedAt` is the workflow instance's `updated_at`, both as ISO-8601 UTC.
8. THE Public_Timeline SHALL contain one entry `{ at, status }` for each history row of kind `START`, and for each movement row of kind `ACTION`, `SKIPPED` or `AUTO` whose `to_status` is not null and differs from its `from_status`, in order of `event_time` then `seq`. `at` is the row's `event_time` and `status` is its `to_status` label. THE Skeleton_Backend SHALL include no other history kind (`EVENT_DEFERRED`, `EVENT_FAILED`, `EXPRESSION_ERROR` never appear). Movement kinds other than `ACTION` are included because the router finalises `to_status` on whichever movement row reaches the entered step, so a status change after a skipped or AUTO step is recorded on that row.
9. THE Track_Response SHALL NOT contain remarks, attachments, actor ids or names, assignee ids or names, step codes, action codes, due dates, history `details`, field values other than `title`, the contact email, the instance id or the record id, anywhere in the body.
10. THE Skeleton_Backend SHALL send `Cache-Control: no-store` on every Track_Endpoint response.
11. THE Skeleton_Backend SHALL write no log line containing the email or the reference, and SHALL record no audit entry for failed lookups.

### Requirement 9: Tracking in the app definition

**User Story:** As an app author, I want to switch tracking on per entity and name the contact field, so that each app decides what a citizen may do.

#### Acceptance Criteria

1. THE schema `schema/app-definition.schema.json` SHALL add the optional entity property `tracking`, an object with `additionalProperties: false`, the required property `contactField` (`$ref` fieldCode), and a description saying it enables public status tracking and names the EMAIL field the citizen must supply.
2. THE Skeleton_Backend SHALL read `tracking` into the Java model (`Entity` gets a `Tracking tracking` component, null when absent, with a secondary constructor that keeps existing call sites unchanged), and SHALL keep it in the stored document, the definition read projections and the Validator input.
3. THE Validator SHALL reject a definition, listing every error, when an entity has `tracking` and: the field named by `contactField` does not exist in the entity (rule `BAD_TRACKING_FIELD`); or the field exists but its type is not `EMAIL` (rule `TRACKING_FIELD_NOT_EMAIL`); or the entity has no `referencePrefix` (rule `TRACKING_NEEDS_REFERENCE`, because a record without a Reference_No cannot be looked up).
4. THE Skeleton_Backend SHALL make the `startsWorkflow` form of a tracked entity unaffected: tracking adds no required field, so a form that omits the Contact_Field is allowed, and a record without a Contact_Field value is simply not trackable (Requirement 8.1 fails, giving 404).
5. THE Public_Subset SHALL add `trackingEnabled: true` to each public form whose entity has a Tracking_Config, and SHALL NOT expose `contactField`. For other forms the property is absent.
6. THE repository SHALL update `examples/grievance.app.json` (add `"tracking": { "contactField": "contactEmail" }` to the `GRIEVANCE` entity), the TypeScript types of the Skeleton_Frontend, and the steering file `.kiro/steering/app-definition.md` in the same change as the schema. The steering file includes the schema by `#[[file:schema/app-definition.schema.json]]`, so only its prose changes: a "Public tracking" behaviour section and the new validator rules in the cross-reference list. THE repository SHALL NOT embed a copy of the schema in the steering file.
7. THE example `examples/grievance.app.json` SHALL still pass schema validation and the Validator, and the app-assets example SHALL stay unchanged because it needs no tracking.

### Requirement 10: Rate limiting on the Track endpoint

**User Story:** As an operator, I want the Track endpoint limited per client, so that nobody can guess references and emails at speed.

#### Acceptance Criteria

1. THE Skeleton_Backend SHALL allow at most `skeletonkey.track.rate-limit.per-minute` (default 10) requests to the Track_Endpoint per Client_IP in any window of 60 seconds, and SHALL count every request, whatever its outcome (200, 404, 422).
2. THE Skeleton_Backend SHALL take the Client_IP from the header `X-Real-IP` only when the direct remote address of the connection is a Trusted_Proxy, and in every other case (untrusted remote address, header missing, header not an IP literal) from the remote address itself. Trusted_Proxies are configured by `skeletonkey.trusted-proxies`, a comma-separated list of IP addresses and CIDR ranges, default `127.0.0.0/8,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`. An invalid entry SHALL stop the application at startup, naming the property. The header name is fixed because `skeleton/frontend/nginx.conf` sets exactly that header from the connecting address and overwrites any value sent by the client.
3. IF a request exceeds the limit, THEN THE Skeleton_Backend SHALL respond with HTTP status 429, code `RATE_LIMITED`, the Common_Error_Body and a `Retry-After` header in whole seconds (at least 1), and SHALL NOT run the lookup.
4. THE Skeleton_Backend SHALL run the limiter before body parsing, so malformed bodies are counted too.
5. THE Skeleton_Backend SHALL keep the limiter state in memory only, with a bounded number of tracked addresses (default 10 000, evicting the least recently seen), so a flood of distinct addresses cannot exhaust the JVM (`-Xmx384m`).
6. THE Skeleton_Backend SHALL keep counts per Client_IP independent: one address being limited SHALL NOT limit another.
7. THE Skeleton_Backend SHALL NOT apply this limiter to any other endpoint.
8. THE limiter SHALL read time from an injectable `Clock`, so tests can control it.
9. THE `docker-compose.yml` SHALL publish the backend port as `"127.0.0.1:8080:8080"`, so the backend is reachable from outside the host only through the reverse proxy.

### Requirement 11: Public tracking page

**User Story:** As a citizen, I want a simple page to check my request, so that I know where it stands.

#### Acceptance Criteria

1. THE Skeleton_Frontend SHALL add the route `/public/:app/track` (lazy, no guard), listed before `public/:app/:form` in `app.routes.ts` so `track` is not read as a form code.
2. THE page SHALL show a form with the reference and the email, both required, with the email checked for format, and a "Check status" button disabled while the form is invalid or a request is running.
3. THE page SHALL apply the app's branding (primary colour, logo, theme) the same way as the public form page, using the Public_Subset.
4. WHEN the API responds 200, THE page SHALL show a result card with the reference, the title, a status chip (label, in the status colour, with a fallback colour per category when `color` is null), the created date, the last-updated date and a timeline (NG-ZORRO timeline) of `at` and `status` entries in the order received.
5. WHEN the API responds 404, THE page SHALL show one message ("We could not find a request with that reference and email. Check both and try again.") that does not say which of the two was wrong.
6. WHEN the API responds 429, THE page SHALL show "Too many attempts. Please wait a minute and try again." and disable the button for the number of seconds in `Retry-After`. WHEN it responds 422, THE page SHALL show the field violations. For any other failure THE page SHALL show a generic error.
7. THE page SHALL read the query parameter `reference` to prefill the reference field, and SHALL NOT prefill or accept the email from the URL, and SHALL NOT store the email or the result in local storage, session storage or any cookie.
8. THE page SHALL render all values as text (no `innerHTML`), and SHALL render the timeline only from `at` and `status`.

### Requirement 12: Link from the public form success page

**User Story:** As a citizen, I want to jump from the confirmation straight to tracking, so that I do not have to retype the number.

#### Acceptance Criteria

1. WHEN a public form is submitted successfully and the form has `trackingEnabled: true` in the Public_Subset, THE success view of `PublicFormPage` SHALL show a link "Track this request" to `/public/:app/track` with the query parameter `reference` set to the returned Reference_No, in addition to the existing reference number text.
2. WHERE the form has no `trackingEnabled`, THE success view SHALL stay as it is, with no link.
3. THE link SHALL NOT include the email or any other submitted value.
4. WHERE the link is shown, THE success view SHALL show the fixed text "You will need the email address you gave on the form." (the contact field's name is not exposed, so the text does not use it).

### Cross-cutting

### Requirement 13: Correctness properties and tests

**User Story:** As a maintainer, I want the security-relevant rules checked by properties and on real PostgreSQL, so that regressions are caught.

#### Acceptance Criteria

1. THE test suite SHALL contain jqwik property tests for Properties 1 to 12 and 15 to 17 of the design, and fast-check property tests for Properties 13 and 14.
2. THE test suite SHALL contain Testcontainers (PostgreSQL 16) tests for every new SQL statement: the user search/filter/sort/page query, user update with role replacement, password reset, the tracking match query, the public timeline query and the status colour lookup; plus an end-to-end assertion that a deactivated user is skipped by both assignment strategies and keeps visible Open_Tasks.
3. THE existing test suite SHALL keep passing.
4. THE tests SHALL not need network access beyond Testcontainers' image pull, and SHALL use the shared jqwik generators (`DefinitionArbitraries`) where they generate definitions.
