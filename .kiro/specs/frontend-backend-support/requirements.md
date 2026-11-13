# Requirements Document

## Introduction

The Frontend Backend Support feature adds to the Skeleton Backend (`skeleton/backend`, Java package `dev.skeletonkey`) the capabilities that the Skeleton Frontend lists as Backend_Dependencies BD-1 to BD-6 and BD-8 in `.kiro/specs/skeleton-frontend/requirements.md` (Requirement 17). The JSON shapes follow the Data Models section of `.kiro/specs/skeleton-frontend/design.md`.

The feature is additive. It adds endpoints and response properties and one optional schema property. It changes no existing endpoint path, no existing response property, no database migration and no routing behaviour. All 237 existing tests keep passing.

Where the frontend design and the existing backend DTOs name a property differently (for example the history entry properties `actionCode` and `eventTime`), the existing backend names stay unchanged and only the properties listed in this document are added. The frontend aligns its types to the backend, as its design states.

Scope: (1) the current-user endpoint; (2) the workflow summary on record list items and the record view; (3) `assigneeName` on the Instance_View and `actorName` on History_Entries; (4) the definition version list and app code list; (5) app branding in the Restricted_Definition and the Public_Subset; (6) the optional `widget` property on schema fields; (7) the validate-without-storing endpoint.

Out of scope: file upload and download (BD-7), changes to the Router, the Validator rules, the SLA scheduler, and the Angular frontend itself.

Conventions that apply: configuration only in `application.properties`; errors only through `ApiExceptionHandler` with the common `ApiError` body; layering `web` → `service` → `repository` with no business logic in controllers; the schema file `schema/app-definition.schema.json` is the single source of truth, so a schema change updates the Java model, the TypeScript types, the publish-time Validator and `examples/` in the same change; tests use JUnit 5, Testcontainers for PostgreSQL and jqwik.

## Glossary

- **Skeleton_Backend**: The Spring Boot application in `skeleton/backend`.
- **Principal**: The authenticated user (user id and role codes) of a request, or the anonymous public role.
- **Platform_Admin**: The role named by `skeletonkey.admin-role` (default `PLATFORM_ADMIN`).
- **User_Store**: The persisted users, each with a user id, user name, display name, role codes and an `active` flag.
- **Me_Endpoint**: `GET /api/auth/me`.
- **Current_User_Response**: The Me_Endpoint body `{ userId, displayName, roles[] }`.
- **Record_API**: The endpoints under `/api/apps/{app}/entities/{entity}/records`.
- **Record_Item**: One element of the `items` array of a record list response, or the body of a record read response. Both are the existing record view.
- **Workflow_Summary**: The properties `instanceId`, `step`, `stepLabel`, `status`, `statusColor`, `assignee`, `assigneeName`, `dueAt` and `ended` added to a Record_Item.
- **Instance_View**: The existing response of `GET /api/instances/{id}`.
- **History_Entry**: The existing audit row returned by `GET /api/instances/{id}/history`.
- **Display_Name**: The `displayName` of a user in the User_Store.
- **Workflow_Instance**: A running workflow bound to one Record and pinned to one Definition_Version and workflow code.
- **Definition_Version**: A stored whole App_Definition identified by app code and version number, with status DRAFT or PUBLISHED and a Content_Hash.
- **Version_Reference**: The existing representation of a Definition_Version without its document: `id`, `version`, `status`, `contentHash` and timestamps.
- **Version_List_Endpoint**: `GET /api/apps/{app}/definitions`.
- **App_List_Endpoint**: `GET /api/definitions/apps`.
- **Validate_Endpoint**: `POST /api/definitions/validate`.
- **Validator**: The publish-time component that checks structural and cross-reference rules of an App_Definition.
- **Violation**: One entry of a validation report with `field` (JSON Pointer or element location), `rule` and `message`.
- **Validation_Report**: The ordered list of Violations produced for a document.
- **Restricted_Definition**: The role-filtered projection returned by `GET /api/apps/{app}/definition`.
- **Public_Subset**: The anonymous projection returned by `GET /api/public/apps/{app}`.
- **Branding**: The optional `app.branding` object of an App_Definition with the optional properties `primaryColor`, `logo` and `theme`.
- **Widget_Key**: The optional `widget` string on a schema `field`, naming a frontend widget.
- **Definition_Loader**: The component that parses, schema-checks and stores App_Definitions.
- **Status_Label**: A label declared in the `statuses` of a workflow, with an optional `color`.
- **Common_Error_Body**: The `ApiError` JSON returned by `ApiExceptionHandler`.

## Requirements

### Requirement 1: Current user endpoint

**User Story:** As a frontend developer, I want one endpoint that returns the signed-in user, so that the UI can show the display name and roles after login and after a page reload.

#### Acceptance Criteria

1. WHEN an authenticated Principal sends `GET /api/auth/me` with a valid JWT, THE Skeleton_Backend SHALL respond with HTTP status 200 and a Current_User_Response holding the user id, the Display_Name and the role codes of that user as stored in the User_Store.
2. THE Skeleton_Backend SHALL return in the Current_User_Response exactly the properties `userId`, `displayName` and `roles`, where `userId` is a non-empty string, `displayName` is a string of 1 to 120 characters and `roles` is an array of zero or more role code strings, and SHALL NOT return the password, the password hash, the user name or the token.
3. IF a request to the Me_Endpoint carries a missing, expired or tampered JWT, THEN THE Skeleton_Backend SHALL respond with HTTP status 401 and the Common_Error_Body, and SHALL return no user data.
4. IF the JWT of a request to the Me_Endpoint is valid but the user is absent from the User_Store or has `active` set to false, THEN THE Skeleton_Backend SHALL respond with HTTP status 401 and the Common_Error_Body, and SHALL return no user data.
5. IF the role codes of a user in the User_Store differ from the role codes in the JWT, THEN THE Skeleton_Backend SHALL return only the role codes from the User_Store and SHALL NOT merge in the role codes from the JWT.
6. THE Skeleton_Backend SHALL list the Me_Endpoint in the OpenAPI description with the method GET, a 200 response whose schema is the Current_User_Response, and a 401 response that uses the Common_Error_Body.

### Requirement 2: Workflow summary on records

**User Story:** As a frontend developer, I want each record to carry its workflow state, so that tables, boards and inboxes can show step, status, assignee and due time without one request per record.

#### Acceptance Criteria

1. WHEN a record list response is returned, THE Record_API SHALL include in every Record_Item the Workflow_Summary properties `instanceId`, `step`, `stepLabel`, `status`, `statusColor`, `assignee`, `assigneeName`, `dueAt` and `ended`, in addition to the existing properties of the Record_Item.
2. WHEN a record read response is returned, THE Record_API SHALL include the same Workflow_Summary properties in the response body.
3. WHILE a Record has a Workflow_Instance, THE Record_API SHALL set `instanceId` to the id of that Workflow_Instance, `step` to the code of its current step, `status` to its current Status_Label text, and `ended` to true if and only if the Workflow_Instance has ended.
4. WHILE a Record has a Workflow_Instance, THE Record_API SHALL set `stepLabel` to the `label` of the current step in the pinned Definition_Version and, WHERE that step declares no `label`, to the step code.
5. WHILE a Record has a Workflow_Instance, THE Record_API SHALL set `statusColor` to the `color` of the Status_Label declared in the pinned workflow and, WHERE that Status_Label declares no `color`, to null.
6. WHILE a Record has a Workflow_Instance whose current step has an assignee, THE Record_API SHALL set `assignee` to the user id of the assignee and `assigneeName` to the Display_Name of that user.
7. WHILE a Record has a Workflow_Instance whose current step has no assignee (including an unclaimed ROLE_QUEUE step), THE Record_API SHALL set `assignee` and `assigneeName` to null.
8. IF the assignee user id has no matching user in the User_Store, THEN THE Record_API SHALL set `assigneeName` to null, set `assignee` to the user id, and return the Record_Item without raising an error.
9. WHILE a Record has a Workflow_Instance whose current step has a due time, THE Record_API SHALL set `dueAt` to that due time in UTC ISO 8601 format with millisecond precision (for example `2026-03-01T09:30:00.000Z`), and otherwise to null.
10. WHEN a Workflow_Instance has ended, THE Record_API SHALL set `ended` to true, set `dueAt`, `assignee` and `assigneeName` to null, and set `status` to the Status_Label set by the END outcome.
11. WHILE a Record has no Workflow_Instance, THE Record_API SHALL set `instanceId`, `step`, `stepLabel`, `status`, `statusColor`, `assignee`, `assigneeName` and `dueAt` to null and `ended` to false.
12. THE Record_API SHALL derive `stepLabel` and `statusColor` from the Definition_Version pinned by the Workflow_Instance and not from any other Definition_Version of the same app.
13. THE Record_API SHALL return the Workflow_Summary properties on every response of the create, replace and patch endpoints that already return a Record_Item.
14. WHEN a list request returns a page of N Record_Items, with N from 1 to the maximum page size, THE Record_API SHALL load the Workflow_Instances and assignee Display_Names of that page with a number of database queries that is identical for N = 1 and for N = the maximum page size.
15. THE Record_API SHALL leave the existing properties of the Record_Item, the paging properties `page`, `size`, `total` and `sort`, and the existing filter, sort and scope behaviour unchanged.
16. IF a Principal may not already see a Record_Item, THEN THE Record_API SHALL omit that item and its Workflow_Summary, with the same outcome as before this feature.
17. THE Record_API SHALL return no Workflow_Summary on any endpoint open to the public role.
18. WHILE a Workflow_Instance has several current steps (a PARALLEL group), THE Record_API SHALL set `step`, `stepLabel`, `assignee`, `assigneeName` and `dueAt` from the same single current step, which is the first open step in declaration order in the pinned workflow.

### Requirement 3: Display names on the Instance_View and history

**User Story:** As a frontend developer, I want names next to user ids in the instance view and history, so that the UI shows people instead of identifiers.

#### Acceptance Criteria

1. WHEN an Instance_View is returned and its `assignee` matches a user in the User_Store, THE Skeleton_Backend SHALL include the property `assigneeName` holding the Display_Name of that user, in addition to the existing properties.
2. IF the Instance_View has no `assignee`, THEN THE Skeleton_Backend SHALL set `assigneeName` to null.
3. IF the `assignee` of an Instance_View has no matching user in the User_Store, THEN THE Skeleton_Backend SHALL set `assigneeName` to null, keep `assignee`, and raise no error.
4. WHEN the History_Entries of a Workflow_Instance are returned, THE Skeleton_Backend SHALL include in each entry whose `actorId` matches a user in the User_Store the property `actorName` holding the Display_Name of that user, in addition to the existing properties.
5. IF the `actorId` of a History_Entry is null, is empty, is the reserved system actor, or has no matching user in the User_Store, THEN THE Skeleton_Backend SHALL set `actorName` to null, keep `actorId`, and raise no error.
6. WHEN the History_Entries of a Workflow_Instance are returned, THE Skeleton_Backend SHALL resolve all Display_Names with at most 1 User_Store query, whether the response holds 1 entry or 1,000 entries.
7. THE Skeleton_Backend SHALL return the History_Entries in the same order, with the same count and with the same values of the existing properties as before this feature, and SHALL persist no Display_Name in the history table, so that the History_Entries remain immutable.
8. WHEN the History_Entries are read twice in a row with no change to the User_Store and no new entry, THE Skeleton_Backend SHALL return identical entries in identical order on both reads.
9. WHEN the Display_Name of a user changes in the User_Store, THE Skeleton_Backend SHALL return the new Display_Name as `actorName` for old and new History_Entries of that user, and as `assigneeName` where that user is the assignee, on the next read, without rewriting any history row.

### Requirement 4: Definition version list and app code list

**User Story:** As a platform administrator, I want to list the apps and the versions of an app, so that I can open, edit and publish them from the admin screens.

#### Acceptance Criteria

1. WHEN a Platform_Admin sends `GET /api/definitions/apps`, THE Skeleton_Backend SHALL respond with HTTP status 200 and a JSON array of the app codes that have at least one Definition_Version, sorted ascending by Unicode code point, with each code exactly once.
2. IF no Definition_Version exists, THEN THE Skeleton_Backend SHALL respond to the App_List_Endpoint with HTTP status 200 and the empty array `[]`.
3. WHEN a Platform_Admin sends `GET /api/apps/{app}/definitions`, THE Skeleton_Backend SHALL respond with HTTP status 200 and a JSON array holding one Version_Reference for every Definition_Version of that app, sorted by version number descending, with no pagination and no omitted versions.
4. THE Skeleton_Backend SHALL include in each Version_Reference of the Version_List_Endpoint the properties `id`, `version`, `status` (DRAFT or PUBLISHED), `contentHash`, `createdAt` and `publishedAt`, with timestamps in UTC ISO 8601 format with exactly three fractional digits (for example `2026-01-31T09:30:15.123Z`), with `publishedAt` set to null while the status is DRAFT, and SHALL NOT include the document.
5. IF the app code of a Version_List_Endpoint request has no Definition_Version or does not match the app code pattern, THEN THE Skeleton_Backend SHALL respond with HTTP status 404 and the Common_Error_Body whose message states that the app was not found.
6. IF a Principal without the Platform_Admin role sends a request to the App_List_Endpoint or the Version_List_Endpoint, THEN THE Skeleton_Backend SHALL respond with HTTP status 403 and the Common_Error_Body, and the response SHALL NOT disclose whether the app exists.
7. IF a request to the App_List_Endpoint or the Version_List_Endpoint carries a missing, expired or tampered JWT, THEN THE Skeleton_Backend SHALL respond with HTTP status 401 and the Common_Error_Body.
8. THE Skeleton_Backend SHALL create, update, delete and change the status of no Definition_Version when it serves either endpoint.
9. THE Skeleton_Backend SHALL list both endpoints in the OpenAPI description, including the 200, 401 and 403 responses of both and the 404 response of the Version_List_Endpoint.

### Requirement 5: Branding in the Restricted_Definition and the Public_Subset

**User Story:** As a frontend developer, I want the app branding in both definition projections, so that the shell and the public form page can apply the app theme, colour and logo.

#### Acceptance Criteria

1. WHEN the Restricted_Definition is returned and the App_Definition declares `app.branding`, THE Skeleton_Backend SHALL include in the `app` object the `branding` object with every property the App_Definition declares among `primaryColor`, `logo` and `theme`, with values equal to the declared values.
2. WHEN the Public_Subset is returned and the App_Definition declares `app.branding`, THE Skeleton_Backend SHALL include in the `app` object the `branding` object with the same properties and values as in criterion 1.
3. WHERE the App_Definition declares no `app.branding`, THE Skeleton_Backend SHALL omit the `branding` property from the `app` object of both projections.
4. WHERE the App_Definition declares `app.branding` with only some of its properties, THE Skeleton_Backend SHALL include only those properties and omit the others.
5. THE Skeleton_Backend SHALL keep the existing `app` properties `code`, `name` and `description` and every other part of both projections unchanged, so that a response from a definition without branding is identical to the response before this feature.
6. WHEN a projection is built, THE Skeleton_Backend SHALL take the branding from the same Definition_Version as the rest of that projection, that is the highest-numbered PUBLISHED version or the requested published version.
7. THE Skeleton_Backend SHALL keep the Public_Subset free of every property that is not on its allow-list, with `app.branding` holding only `primaryColor`, `logo` and `theme` added to that allow-list and no other addition.
8. IF the App_Definition declares `app.branding` as an empty object, THEN THE Skeleton_Backend SHALL include `branding` as an empty object in both projections.

### Requirement 6: Optional widget property on schema fields

**User Story:** As an app author, I want to name a custom frontend widget on a field, so that an app can replace the built-in control without changing the skeleton.

#### Acceptance Criteria

1. THE schema `schema/app-definition.schema.json` SHALL define an optional `widget` property on the `field` definition, of type string, 1 to 64 characters, matching the pattern `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$`.
2. WHEN a document is submitted whose field declares a `widget` value that matches the pattern, THE Definition_Loader SHALL accept the field with zero schema violations.
3. IF a document declares a field `widget` that is not a string, is empty, is longer than 64 characters or does not match the pattern, THEN THE Definition_Loader SHALL reject the document with HTTP status 422 and a Validation_Report with at least one Violation at the JSON Pointer of the `widget` property, and SHALL store no Definition_Version.
4. THE Java model of a field SHALL hold the Widget_Key as an optional value, SHALL be parsed from and written back to the document without loss, and SHALL leave the Widget_Key absent when the document omits it.
5. FOR ALL schema-valid App_Definitions, including those whose fields declare a Widget_Key, serializing then parsing then serializing SHALL produce a document that is JSON-equal to the original, and a field that omits `widget` SHALL NOT gain a `widget` key, including a key with the value null (round-trip property).
6. WHEN a stored Definition_Version is read, THE Definition_Loader SHALL return the field `widget` values exactly as submitted, character for character with case preserved.
7. THE Validator SHALL accept a field with a Widget_Key without reporting a Violation, including a Widget_Key that matches no registered widget, because widgets are registered in the frontend and the Skeleton_Backend has no widget registry.
8. WHEN the Restricted_Definition or the Public_Subset is returned, THE Skeleton_Backend SHALL include the `widget` property of each returned field that declares one and SHALL omit the `widget` property for fields that do not declare one.
9. THE Skeleton_Backend SHALL leave `examples/grievance.app.json` unchanged and accept it with zero schema violations and zero Validator violations.
10. WHERE the frontend project contains TypeScript types for the App_Definition field, THE change SHALL add the optional `widget` string to those types in the same change as the schema.
11. THE jqwik definition generator used by the Validator tests SHALL generate fields both with and without a Widget_Key, so that the round-trip property of criterion 5 and the accept property of criterion 7 cover both cases.

### Requirement 7: Validate a definition without storing it

**User Story:** As a platform administrator, I want to validate a definition while I edit it, so that I see the publish-time errors before saving.

#### Acceptance Criteria

1. WHEN a Platform_Admin sends `POST /api/definitions/validate` with a JSON body that is well-formed and passes the schema, THE Skeleton_Backend SHALL respond with HTTP status 200 and the Validation_Report that the Validator produces for that document, as a JSON array of Violations each holding `field`, `rule` and `message`, where cross-reference violations (unknown codes, unreachable steps, unset statuses) are Violations in the 200 response and not error responses.
2. WHEN the document has no violations, THE Skeleton_Backend SHALL respond with HTTP status 200 and an empty array.
3. THE Skeleton_Backend SHALL produce for the same document the same Violations in the same order as the Publish operation produces, including the handler catalog check and the unsupported-step check.
4. WHEN a Platform_Admin sends a well-formed JSON object that violates the schema, THE Skeleton_Backend SHALL respond with HTTP status 200 and a Validation_Report listing every schema violation (not only the first) with its JSON Pointer path in `field`, the schema rule code in `rule` and a message describing the violated constraint.
5. IF the body is empty, is not well-formed JSON, or has a top-level value that is not an object, THEN THE Skeleton_Backend SHALL respond with HTTP status 400 and the Common_Error_Body, and for a parse error the Common_Error_Body SHALL include the 1-based line number and column number of the first parse error.
6. THE Skeleton_Backend SHALL store no Definition_Version, change no Definition_Version, and write no audit log entry when it serves the Validate_Endpoint.
7. IF a Principal without the Platform_Admin role sends a request to the Validate_Endpoint, THEN THE Skeleton_Backend SHALL respond with HTTP status 403 and the Common_Error_Body, and SHALL NOT evaluate the body.
8. IF a request to the Validate_Endpoint carries a missing, expired or tampered JWT, THEN THE Skeleton_Backend SHALL respond with HTTP status 401 and the Common_Error_Body, and SHALL NOT evaluate the body.
9. WHEN the same body is sent to the Validate_Endpoint twice, THE Skeleton_Backend SHALL return equal Validation_Reports in equal order (idempotence).
10. FOR ALL documents, THE Validation_Report returned by the Validate_Endpoint for a document SHALL contain an entry for every Violation that Publish would report for the same document stored as a DRAFT Definition_Version (consistency property).
11. WHEN a document with an app code and version that already exist is sent to the Validate_Endpoint, THE Skeleton_Backend SHALL return the same report as for a document with identical content, SHALL report no duplicate-version Violation, and SHALL leave the stored version unchanged.
12. THE Skeleton_Backend SHALL list the Validate_Endpoint in the OpenAPI description.
13. IF the body of a request to the Validate_Endpoint is larger than 1 MiB (1,048,576 bytes), THEN THE Skeleton_Backend SHALL respond with HTTP status 413 and the Common_Error_Body, and SHALL NOT parse or validate the body.

### Requirement 8: Compatibility, security and test coverage

**User Story:** As a maintainer, I want the new capabilities to leave the existing behaviour intact and to be tested in the existing style, so that the engine stays trustworthy.

#### Acceptance Criteria

1. WHEN the build's test task runs after this feature, THE Skeleton_Backend SHALL pass all 237 tests that exist before this feature with 0 failures, 0 errors and 0 skipped or deleted tests, without modifying their expected behaviour, other than adding the new properties to expected response shapes where a test compares the whole body.
2. THE Skeleton_Backend SHALL add 0 database migration files for this feature.
3. THE Skeleton_Backend SHALL read 0 new configuration properties for this feature.
4. THE Skeleton_Backend SHALL perform the Platform_Admin check of Requirements 4 and 7 through the existing admin guard, so that no admin endpoint does its own role comparison and the role named by `skeletonkey.admin-role` governs all admin endpoints in one place.
5. IF any of the new endpoints fails with a 5xx status, THEN THE Skeleton_Backend SHALL return the Common_Error_Body with a request identifier, with no stack trace, exception class name, SQL text or file path, and with stored data unchanged.
6. THE Skeleton_Backend SHALL include JUnit 5 integration tests on Testcontainers PostgreSQL that cover, for each of the Me_Endpoint, the App_List_Endpoint, the Version_List_Endpoint and the Validate_Endpoint, at least one success case and one case for each failure status (400, 401, 403, 404) that Requirements 1, 4 and 7 define for that endpoint.
7. THE Skeleton_Backend SHALL include integration tests that cover each of the 5 Workflow_Summary cases of Requirement 2 (instance running, instance ended, assignee absent, assignee unknown, record without instance) on both the list and the read response, and the `assigneeName` and `actorName` cases of Requirement 3.
8. THE Skeleton_Backend SHALL include a jqwik property verifying the consistency property of Requirement 7.10 and a jqwik property verifying the round-trip of Requirement 6.5, each run over at least 100 generated definitions that include Widget_Keys.
9. THE Skeleton_Backend SHALL include a test verifying that the query count of a record list request is the same for 1 item and for 50 items, and that the query count of a history request does not grow with the number of entries, as required by Requirements 2.14 and 3.6.
10. THE Skeleton_Backend SHALL include a test verifying that every property returned in the Public_Subset is in its allow-list, that the Public_Subset contains the `branding` object and the field `widget` values, and that a definition with a property outside the allow-list yields 0 such properties in the Public_Subset.
