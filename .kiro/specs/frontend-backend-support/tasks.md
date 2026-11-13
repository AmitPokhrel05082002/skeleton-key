# Implementation Plan: Frontend Backend Support

## Overview

Implementation language is Java 21 with Spring Boot 3 (package `dev.skeletonkey`), as fixed by the design. All paths are relative to `skeleton/backend/` unless stated otherwise, and the work is additive: new endpoints, new response properties and one optional schema property. No Flyway migration is added, no `application.properties` key is added, and `RecordService`, `RecordView` and `RecordPage` stay unchanged (a new assembler enriches their output). The steering file `.kiro/steering/app-definition.md` must NOT be edited, because it references the schema file instead of embedding it; only `schema/app-definition.schema.json` changes for `widget`. Requirement 5 (branding) needs no production change, only tests. The 14 correctness properties from the design are each their own sub-task, placed close to the code they check. Property tests 1.4 (Property 9) and 7.3 (Property 11) are required by Requirement 8.8; the other property tests, example tests and integration tests are optional. Commands use Windows CMD syntax (`mvnw.cmd`), run from `skeleton\backend`.

## Tasks

- [ ] 1. Widget property and branding verification
  - [ ] 1.1 Add `widget` to the schema, the Java model and the projection
    - In `schema/app-definition.schema.json` add to `$defs.field.properties` an optional `widget` string (`minLength` 1, `maxLength` 64, `pattern` `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$`); keep `additionalProperties: false`; do NOT edit `.kiro/steering/app-definition.md`
    - Make sure the classpath copy of the schema used by `DefinitionLoader` picks up the change; leave `examples/grievance.app.json` unchanged
    - Add `String widget` as the 15th component of `Field` in `domain/model/Field.java` and keep a secondary 14-argument constructor that sets `widget` to null, so no existing call site changes
    - In `DefinitionParser.field` pass `optStr(n, "widget")`
    - In `DefinitionReadService` make `fieldNode(Field)` a package-private static method and add `putIfNotNull(n, "widget", f.widget())`
    - Add no Validator rule for `widget`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.6, 6.7, 6.8, 6.9, 6.10_

  - [ ] 1.2 Extend the shared jqwik generators
    - In `DefinitionArbitraries.Builder` (test sources) set a pattern-valid `widget` (mixed case, `_`, `-`) on about half of the generated fields
    - Generate `app.branding` as absent, empty, partial or full (`primaryColor`, `logo`, `theme`)
    - _Requirements: 6.11, 5.1, 5.3, 5.4, 5.8_

  - [ ]* 1.3 Write property test for widget schema acceptance
    - Create `domain/load/WidgetSchemaPropertyTest`
    - **Property 8: Widget schema acceptance**
    - **Validates: Requirements 6.2, 6.3**

  - [ ] 1.4 Write property test for widget round trip
    - Create `domain/load/WidgetRoundTripPropertyTest` (`tries = 200`)
    - **Property 9: Widget round trip**
    - **Validates: Requirements 6.4, 6.5, 6.6, 8.8**

  - [ ]* 1.5 Write property test for widgets in the Validator and the projections
    - Create `definition/WidgetProjectionPropertyTest` in the same package as `DefinitionReadService` to reach `fieldNode`
    - **Property 10: Widgets pass the validator and reach the projections**
    - **Validates: Requirements 6.7, 6.8**

  - [ ] 1.6 Write property test for branding projection and extend `DefinitionReadTest`
    - Create `definition/BrandingProjectionPropertyTest`
    - **Property 7: Branding is projected as declared in both projections**
    - Add example cases to `DefinitionReadTest`: branding full, partial, empty and absent in both projections; `widget` in field objects of both projections; every Public_Subset key is on the allow-list and a definition with an extra property yields none of it
    - If a test finds a gap, fix it locally in `DefinitionReadService.appNode`
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 8.10**

- [ ] 2. Current user endpoint
  - [ ] 2.1 Implement `GET /api/auth/me`
    - Create `web/CurrentUserResponse` record (`userId`, `displayName`, `roles`)
    - Add `AuthService.me(Principal)`: `UserRepository.findById`, filter `active`, else throw `BadCredentialsException`; roles come only from the stored user, sorted
    - Add `AuthController.me()` with `@Operation` and `@ApiResponse` for 200 (`CurrentUserResponse`) and 401 (`ApiError`); leave `SecurityConfig` unchanged
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [ ]* 2.2 Write property test for the current user
    - Create `app/CurrentUserPropertyTest`
    - **Property 1: Current user comes from the store**
    - **Validates: Requirements 1.1, 1.4, 1.5**

  - [ ] 2.3 Write web tests for the Me endpoint
    - Create `app/AuthMeTest` (in-memory context style of `AuthSecurityTest`): exactly three keys and no `passwordHash`, `username` or `token`; 401 for missing, expired and tampered JWT; 401 for inactive and deleted user; roles differ from JWT
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 8.6_

- [ ] 3. Batch repository reads
  - [ ] 3.1 Add `UserRepository.findDisplayNames`
    - Add `Map<UUID, String> findDisplayNames(Collection<UUID>)` to the port, one `SELECT id, display_name ... WHERE id IN (...)` in `JdbcUserRepository`, empty map without a query for an empty argument; implement in `InMemoryUserRepository` (test sources)
    - _Requirements: 2.14, 3.6_

  - [ ] 3.2 Add `InstanceRepository.findByRecordIds`
    - Add `Map<UUID, InstanceState> findByRecordIds(Collection<UUID>)` to the port, one `SELECT ... WHERE record_id IN (...)` in `JdbcInstanceRepository` reusing its mapper, empty map for an empty argument; implement in `InMemoryInstanceRepository`
    - _Requirements: 2.14_

  - [ ] 3.3 Add definition listing reads
    - Create `persistence/DefinitionVersionInfo` (`id`, `appCode`, `version`, `status`, `contentHash`, `createdAt`, `publishedAt`)
    - Add `findAppCodes()` (`SELECT DISTINCT app_code ... ORDER BY app_code COLLATE "C"`) and `findInfoByApp(String)` (no `document` column, `ORDER BY version DESC`) to `DefinitionRepository`, `JdbcDefinitionRepository` and `InMemoryDefinitionRepository`
    - Extend `DefinitionVersionRef` with `createdAt` and `publishedAt` and add `of(DefinitionVersionInfo)`; keep `of(DefinitionVersionRow)` working
    - _Requirements: 4.1, 4.3, 4.4, 4.8_

- [ ] 4. Workflow summary on records
  - [ ] 4.1 Implement `WorkflowSummary`, `RecordItem` and `RecordItemPage`
    - Create the three records in the `record` package; `WorkflowSummary.of(InstanceState, Workflow, Map<UUID, String>)` and `NONE` as specified in the design; tolerate `pinned == null`
    - `RecordItem` holds the nine `RecordView` components in the same order and names, then the nine summary components; `RecordItem.of(RecordView, WorkflowSummary)`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.15, 2.18_

  - [ ]* 4.2 Write property tests for the summary mapping
    - Create `record/WorkflowSummaryPropertyTest`
    - **Property 2: Workflow summary mapping**
    - **Property 3: Summary never alters the record**
    - **Validates: Requirements 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.10, 2.11, 2.15**

  - [ ] 4.3 Implement `RecordItemAssembler` and wire `RecordController`
    - Create `RecordItemAssembler` (`@Service`) with `item(RecordView)` and `page(RecordPage)`: one `findByRecordIds` call, one `publishedVersion` lookup per distinct pinned `(app, version)`, one `findDisplayNames` call for non-ended assignees (skipped when empty); a missing pinned version gives `pinned = null` instead of failing the page
    - Change `RecordController` list, read, create, replace and patch to return `RecordItemPage` / `RecordItem` through the assembler; no logic in the controller
    - Do not touch `FormController` (public responses carry no summary)
    - Update existing tests that compare whole record bodies only by adding the new keys to the expected shape
    - _Requirements: 2.1, 2.2, 2.12, 2.13, 2.14, 2.15, 2.16, 2.17, 8.1_

  - [ ]* 4.4 Write property test for page enrichment cost
    - Create `record/RecordItemAssemblerQueryCountPropertyTest` with counting wrappers around the in-memory repositories
    - **Property 4: Page enrichment cost does not depend on page size**
    - **Validates: Requirements 2.1, 2.14**

  - [ ]* 4.5 Write web tests for record summaries
    - Cover the five cases (instance running, ended, assignee absent, assignee unknown, record without instance) on both the list and the read response; create, replace and patch return the summary; the public form submit response has no summary keys (`FormSubmitTest`)
    - _Requirements: 2.1, 2.2, 2.7, 2.8, 2.10, 2.11, 2.13, 2.17, 8.7_

- [ ] 5. Display names on the Instance_View and history
  - [ ] 5.1 Add `assigneeName` and `actorName`
    - Add `String assigneeName` after `assignee` in `InstanceView`; fill it in `InstanceQueryService.build` with at most one `findDisplayNames` call
    - Create `app/HistoryEntryView` (same names as `HistoryEntry` plus `actorName`); in `InstanceQueryService.history` keep the stable sort, exclude null and the system actor id, resolve names with one lookup, map to views; add a `UserRepository` constructor parameter
    - `InstanceController.history` returns `List<HistoryEntryView>`; leave `HistoryEntry` unchanged
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.9_

  - [ ]* 5.2 Write property test for history names
    - Create `app/HistoryNamesPropertyTest` with a counting `UserRepository`
    - **Property 5: History names are resolved, never stored, and cost at most one lookup**
    - **Validates: Requirements 3.4, 3.5, 3.6, 3.7, 3.8**

  - [ ]* 5.3 Update and extend `InstanceEndpointsTest`
    - Add `assigneeName` and `actorName` to expected shapes; cover known, absent and unknown assignee; known user, null actor, system actor and unknown user; a renamed user shows the new name on old entries
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.9, 8.1, 8.7_

- [ ] 6. Definition version list and app code list
  - [ ] 6.1 Implement `DefinitionQueryService` and the two list endpoints
    - Create `definition/DefinitionQueryService` with `appCodes()` and `versions(String)` (same `NotFound("App not found")` for an unknown app and for a code that fails `^[A-Z][A-Z0-9_]{0,63}$`)
    - Add `GET /api/definitions/apps` and `GET /api/apps/{app}/definitions` to `DefinitionController`, each starting with `admin.require(RecordController.caller())`; add `@Operation` and `@ApiResponse` for 200, 401, 403 (and 404 for versions)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 8.4_

  - [ ]* 6.2 Write property test for the lists
    - Create `definition/DefinitionQueryPropertyTest`
    - **Property 6: App list and version list are complete and ordered**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 4.8**

  - [ ]* 6.3 Write web tests for the list endpoints
    - Create `DefinitionListEndpointsTest`: 200 for admin, 403 for non-admin with equal bodies for existing and unknown apps, 401, 404 for unknown and malformed code, timestamp pattern `\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z`, `publishedAt` null for DRAFT
    - _Requirements: 4.4, 4.5, 4.6, 4.7, 8.6_

- [ ] 7. Validate a definition without storing it
  - [ ] 7.1 Refactor `DefinitionService` to share the violation report
    - Extract `List<ApiViolation> violationsOf(JsonNode)` and make `publish` use it with its existing messages (`Definition cannot be parsed`, `Definition has N violation(s)`)
    - Add `validateText(String)`: parse (syntax error gives 400 with line and column), non-object gives `BadRequest`, all schema violations returned as the report, otherwise `violationsOf`; no repository write and no audit
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.10, 7.11_

  - [ ] 7.2 Add the bounded body reader, the 413 handler and the endpoint
    - Create `web/BoundedBody` (`MAX_BYTES = 1_048_576` constant, early `Content-Length` check, reads at most `MAX_BYTES + 1` bytes, UTF-8) and `web/PayloadTooLarge`
    - Add the `PayloadTooLarge` handler to `ApiExceptionHandler` (413 `PAYLOAD_TOO_LARGE`)
    - Add `POST /api/definitions/validate` to `DefinitionController`: `admin.require` first, then `service.validateText(BoundedBody.read(request))`; add OpenAPI annotations
    - _Requirements: 7.1, 7.5, 7.7, 7.8, 7.12, 7.13, 8.2, 8.3, 8.4_

  - [ ] 7.3 Write property tests for validate
    - Create `definition/ValidateEndpointPropertyTest` (`tries = 200` for Property 11) using `validDefinitionJson`, `Mutations` and truncated texts
    - **Property 11: Validate equals Publish**
    - **Property 12: Validate is idempotent and has no side effect**
    - **Property 13: Unparseable text is a 400 with a position**
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.9, 7.10, 7.11, 8.8**

  - [ ]* 7.4 Write property test for the body size bound
    - Create `web/BoundedBodyPropertyTest` with byte arrays around the limit
    - **Property 14: Body size bound**
    - **Validates: Requirements 7.13**

  - [ ]* 7.5 Write web tests for the Validate endpoint
    - Create `ValidateEndpointTest`: 200 with `[]` and with violations; 400 for empty, malformed (checks `line`, `column`) and array body; 403 for non-admin, including with an oversized body (body not read); 401; 413 for a body of 1 MiB + 1 that is not parsed; nothing stored and no audit row
    - _Requirements: 7.1, 7.2, 7.5, 7.6, 7.7, 7.8, 7.13, 8.6_

- [ ] 8. Checkpoint - Ensure property and web tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Integration and compatibility tests
  - [ ]* 9.1 Write the Testcontainers PostgreSQL integration test
    - Create `web/FrontendBackendSupportIT` following `ApplicationContextTest` (`postgres:16-alpine`, `@DynamicPropertySource`, seeded users)
    - Cover success and each failure status (400, 401, 403, 404) for the Me, App list, Version list and Validate endpoints; the five record summary cases on list and read; `assigneeName` and `actorName`
    - Count SQL statements through a wrapper: a record list of 1 and of 50 items issues the same number, and a history of 1 and of 200 entries issues one user statement
    - _Requirements: 8.5, 8.6, 8.7, 8.9, 2.14, 3.6_

  - [ ]* 9.2 Write smoke tests for compatibility
    - Check `/v3/api-docs` lists `/api/auth/me`, `/api/definitions/apps`, `/api/apps/{app}/definitions` and `/api/definitions/validate` with the documented responses; the `db/migration` folder still holds only the existing migrations; `SkeletonProperties` and `application.properties` are unchanged; a forced 5xx on a new endpoint returns the `ApiError` body with `requestId` and no stack trace, class name, SQL or path
    - _Requirements: 1.6, 4.9, 7.12, 8.2, 8.3, 8.5_

- [ ] 10. Final checkpoint - Run the full backend test suite
  - From `skeleton\backend` run `mvnw.cmd test` (Docker must be running for the Testcontainers tests)
  - All 237 existing tests must still pass with 0 failures, 0 errors and 0 skipped or deleted tests, and the existing tests may only change by adding the new keys to expected response shapes
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 8.1, 8.2, 8.3_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Required property tests (Requirement 8.8): 1.4 (Property 9) and 7.3 (Property 11, with Properties 12 and 13); 1.6 (Property 7) is also not optional because it carries the branding tests
- Each property test is tagged `// Feature: frontend-backend-support, Property N: <title>` and uses at least 100 tries
- Property tests run on the in-memory repositories and existing generators, no Spring context or database
- The steering file `.kiro/steering/app-definition.md` is not edited; only `schema/app-definition.schema.json` changes for `widget`
- `skeleton/frontend` does not exist yet, so requirement 6.10 (TypeScript type) has nothing to change today
- Single-class commands (CMD): `mvnw.cmd test -Dtest=ValidateEndpointPropertyTest` and `mvnw.cmd test -Dtest=FrontendBackendSupportIT`

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "3.1", "3.2", "3.3", "7.1"] },
    { "id": 1, "tasks": ["1.2", "2.2", "2.3", "4.1", "5.1", "6.1"] },
    { "id": 2, "tasks": ["1.3", "1.4", "1.5", "1.6", "4.2", "4.3", "5.2", "5.3", "6.2", "6.3", "7.2"] },
    { "id": 3, "tasks": ["4.4", "4.5", "7.3", "7.4", "7.5"] },
    { "id": 4, "tasks": ["9.1", "9.2"] }
  ]
}
```
