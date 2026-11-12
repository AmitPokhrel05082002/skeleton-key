# Implementation Plan: Skeleton Backend

## Overview

Implementation language is Java 21 with Spring Boot 3 (package `dev.skeletonkey`), as fixed by the design. The Maven project lives in `skeleton/backend/` (with the Maven wrapper); all source, test and integration test paths below are relative to that directory unless stated otherwise. Persistence uses Spring JDBC (`JdbcClient`), not JPA. The plan builds the pure core first (model, expression engine, Validator, RecordValidator, Router parts), then the ports and in-memory twins, then the Spring services, controllers, auth, startup loaders and integration tests. All library versions in `pom.xml` MUST be pinned exactly (no ranges). Each jqwik property from the design is its own sub-task, placed close to the code it checks. Properties 4, 5, 6, 7, 9, 12, 13, 14, 16 and 22 are required; the remaining property tests, example tests and integration tests are optional.

## Tasks

- [x] 1. Foundation: schema, project scaffold, migrations, web basics
  - [x] 1.1 Verify the schema changes and mark complete
    - The schema changes are already in the repo; no schema edits are expected
    - Verify `workflow.version` is absent from `schema/app-definition.schema.json`
    - Verify `status` is an optional string property on Step and on Transition
    - Verify `examples/grievance.app.json` still validates against the schema
    - _Requirements: 5.9, 3.7, 10.14, 1.10_

  - [x] 1.2 Create the Maven project scaffold in `skeleton/backend/`
    - Create `skeleton/backend/pom.xml` for Spring Boot 3 / Java 21 with exact pinned versions (no ranges) for: spring-boot starters (web, security, jdbc, validation, actuator, test), PostgreSQL driver, `flyway-core` and `flyway-database-postgresql`, `com.networknt:json-schema-validator`, Jackson, `io.github.jamsesso:json-logic-java`, jjwt, `net.jqwik:jqwik`, Testcontainers, AssertJ
    - Add the Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) in `skeleton/backend/`
    - Use Spring JDBC (`JdbcClient`) for persistence, not JPA
    - Create the application class, `application.properties` with the `skeletonkey.*` keys, and `SkeletonProperties` (`@ConfigurationProperties`, `@Validated`, `@Max(100)` on page size, secret `@Size(min = 32)`)
    - Copy `../../schema/app-definition.schema.json` (relative to `skeleton/backend`) to the classpath at build time
    - Create `docker-compose.yml` at the repo root (postgres:16 plus the app built with build context `skeleton/backend`, `restart: unless-stopped`, capped `-Xmx`)
    - Configure Surefire so jqwik properties run in `mvn test` and print the seed on failure; Testcontainers integration tests live under `skeleton/backend/src/test`
    - _Requirements: 20.1, 20.3, 20.4, 20.7, 18.9, 18.10_

  - [x] 1.3 Write Flyway migration `V1__init.sql`
    - Tables: `definition_version`, `app_user`, `user_role`, `record` (with GIN index), `reference_counter`, `workflow_instance` (with `lock_version`), `assignment_cursor`, `history_entry`, `audit_log`
    - Add the trigger that rejects UPDATE and DELETE on `history_entry`
    - Status columns are plain `varchar`, no enum types
    - Add a custom Flyway `FailureAnalyzer` that names the failed migration
    - _Requirements: 20.2, 20.5, 20.8, 12.7, 7.17_

  - [x] 1.4 Implement web foundations
    - `RequestIdFilter` (X-Request-Id in MDC and error bodies)
    - `ApiExceptionHandler` mapping `RouterException`, `ValidationFailed`, `NotFound`, `Conflict`, `OptimisticLockingFailureException` (409 `CONCURRENT_MODIFICATION`), access and authentication errors to the JSON error shape
    - Jackson `InstantSerializer` with `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`
    - `OpenApiConfig`; 405 `NOT_SUPPORTED` handling for history modification
    - _Requirements: 20.6, 12.7, 12.12, 7.18, 9.13_

- [x] 2. Domain model and expression engine
  - [x] 2.1 Implement the immutable domain model and parser
    - Records: `AppDefinition`, `Entity`, `Field`, `Workflow`, `Step`, `Action`, `Transition`, sealed `Event`, runtime `InstanceState`, `HistoryEntry`, `CursorUpdate`, `Principal`
    - `DefinitionParser` from `JsonNode` to the model (no `workflow.version` field), with a cache keyed by `(appCode, version)`
    - _Requirements: 5.9, 5.5, 20.8_

  - [x] 2.2 Implement `ExpressionEngine`
    - `analyze`: in-house walker returning operators, `var` paths and parse errors with JSON Pointers; total for any `JsonNode`; depth cap 64
    - Fixed operator allow-list; `var` root check (`data`, `instance`, `user`)
    - `evaluate` and `evaluateBoolean` delegating to json-logic-java after the allow-list check, with `JsonNode` to `Map`/`List` conversion, wrapping failures in `ExpressionException`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 14.4, 10.2, 15.2_

  - [ ]* 2.3 Write unit tests for the ExpressionEngine wrapper
    - Allow-list rejection before delegation, conversion, dot-path `var`, boolean-only event `when`, library truthiness and missing-path behavior
    - _Requirements: 4.3, 14.4_

- [ ] 3. Validator
  - [x] 3.1 Implement Validator infrastructure and structural rule groups
    - `Validator`, `Violation`, `ValidationReport` (sorted by path, rule, message), rule runner that runs all groups
    - Collections (`DUPLICATE_CODE`, `MULTIPLE_PUBLIC_ROLES`), entities and fields, forms and views rules
    - _Requirements: 2.1, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.13, 2.14_

  - [x] 3.2 Implement workflow graph rules
    - All codes `BAD_START_STEP` to `UNSUPPORTED_STEP`, including `UNKNOWN_STATUS`, `UNSET_STATUS`, reachability BFS, dead ends, ambiguous priority, JOIN mismatch
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14, 3.15, 3.16_

  - [x] 3.3 Implement expression and handler rules
    - `EXPR_PARSE`, `EXPR_OPERATOR`, `EXPR_VAR_ROOT`, `EXPR_FIELD`, `UNKNOWN_HANDLER`, `SET_FIELD_UNKNOWN`, plus `HandlerCatalog` interface
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8_

  - [ ] 3.4 Create shared jqwik generators (test sources)
    - `DefinitionArbitraries.validDefinition()` (1-20 roles, 1-10 entities, 2-30 steps, all field types, supported step types and assignee strategies, every status settable)
    - `Mutations.oneOfTenDefects`, `ExpressionArbitraries.anyJsonLogic` (depth 0-10), `PrincipalArbitraries`, `ActionSequenceArbitraries`
    - _Requirements: 18.1, 18.3, 19.1_

  - [ ] 3.5 Write property test for valid definitions
    - **Property 4: Validator accepts every valid definition**
    - **Validates: Requirements 2.1, 2.3, 18.2**

  - [ ] 3.6 Write property test for injected defects
    - **Property 5: Validator detects each injected defect**
    - **Validates: Requirements 2.2, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.13, 2.14, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14, 3.15, 4.3, 4.4, 4.5, 18.4**

  - [ ] 3.7 Write property test for totality and determinism
    - **Property 6: Validator is total, deterministic and order-insensitive**
    - **Validates: Requirements 2.4, 4.1, 4.2, 4.8, 18.5, 18.6, 18.7**

  - [ ]* 3.8 Write Validator example tests
    - Grievance example publishes with zero violations; `UNSET_STATUS` once per label; `UNKNOWN_STATUS` for Transition and Step status; `min == max` has no violation
    - _Requirements: 1.10, 2.3, 2.12, 3.7, 3.16_

- [ ] 4. Checkpoint - Ensure validator tests pass
  - Ensure all validator tests pass, ask the user if questions arise.

- [ ] 5. Definition loading and versioning
  - [x] 5.1 Implement `DefinitionLoader` parsing, schema check and hashing
    - Jackson parse with line and column from `JsonLocation` (400), networknt draft 2020-12 check mapped to JSON Pointer violations (422), canonical JSON and SHA-256 Content_Hash, `USE_BIG_DECIMAL_FOR_FLOATS`
    - _Requirements: 1.1, 1.2, 1.3, 1.7_

  - [ ] 5.2 Implement `DefinitionService` with repository port
    - `DefinitionRepository` port, JDBC adapter using `JdbcClient` and in-memory twin (test sources)
    - `submit`, `edit` (new DRAFT, 409 for in-place update of PUBLISHED), `read` (404), `publish` (Validator, 422, 409 if not DRAFT, audit entry), `latestPublished`; version = max + 1 with unique constraint and one retry
    - `DefinitionController` for submit, edit, read and publish
    - _Requirements: 1.4, 1.8, 2.2, 2.3, 2.15, 5.1, 5.2, 5.3, 5.4, 5.6, 5.7, 5.8, 12.10_

  - [ ]* 5.3 Write property test for definition round trip
    - **Property 1: Definition serialization round trip**
    - **Validates: Requirements 1.7, 1.9, 18.8**

  - [ ]* 5.4 Write property test for schema violations
    - **Property 3: Schema violations are rejected with pointers**
    - **Validates: Requirements 1.3**

  - [ ]* 5.5 Write property test for version numbering and hash idempotence
    - **Property 2: Version numbering and hash idempotence**
    - **Validates: Requirements 1.4, 1.5, 1.11, 5.1**

  - [ ]* 5.6 Write unit tests for definition endpoints and service
    - Malformed JSON line and column, unknown id 404, publish of non-DRAFT, update of PUBLISHED 409, no published version, missing app or workflow, `workflow.version` absent
    - _Requirements: 1.2, 1.8, 2.15, 5.3, 5.7, 5.8, 5.9_

- [ ] 6. Records
  - [x] 6.1 Implement `RecordValidator`
    - Type table for all field types, CREATE and UPDATE modes, collects missing, unknown and failing fields in one pass
    - _Requirements: 7.3, 7.4, 7.5, 7.6_

  - [ ]* 6.2 Write property test for record validation
    - **Property 8: Record validation reports every failure**
    - **Validates: Requirements 7.3, 7.4, 7.5, 7.6, 11.6, 11.8**

  - [x] 6.3 Implement remaining ports, adapters and in-memory twins
    - `RecordRepository`, `InstanceRepository` (with open-task counts), `HistoryRepository` (append and find only), `AuditRepository`, `UserRepository`, `ReferenceCounter`, `AssignmentCursorRepository`
    - JDBC adapters using `JdbcClient` and in-memory twins under test sources
    - _Requirements: 19.9, 12.7, 7.7_

  - [ ] 6.4 Implement `RecordService` create, read and update, `AuditService` and `RecordController`
    - Create with atomic reference numbers, read (404 when the row is not visible), update (409 when the record has a workflow instance or the app is unpublished)
    - Update is allowed only for entities without a workflow (validated per 7.3-7.6); 409 for records with a workflow instance or an unpublished app
    - Audit entries written in the same transaction
    - `RecordController` endpoints for create, read and update
    - _Requirements: 7.1, 7.2, 7.7, 7.8, 7.9, 7.15, 7.16, 7.17, 12.10, 12.11_

  - [ ] 6.5 Implement `RecordService` listing and extend `RecordController`
    - List with paging (default 20, max 100), parameter-bound equality filters, sort and field name validation (unknown name is reported by parameter)
    - View scopes MINE, ASSIGNED_TO_ME, MY_ROLE_QUEUE and row visibility
    - `RecordController` list endpoint
    - _Requirements: 7.10, 7.11, 7.12, 7.13, 7.14, 7.9_

  - [ ]* 6.6 Write unit tests for records
    - Update rules with and without workflow, paging bounds 0, 1, 100, 101, unknown filter or sort field naming the parameter
    - _Requirements: 7.11, 7.13, 7.15, 7.16_

- [ ] 7. Router
  - [x] 7.1 Implement `FieldRuleResolver`, status resolution and `TransitionSelector`
    - Effective rule (unlisted is `readonly`), `resolveStatus` precedence, selection by priority (default 100) then declaration order, then Action `to`, then next step, with `EXPRESSION_ERROR` entries
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.14, 11.3, 15.4_

  - [x] 7.2 Implement `AssigneeResolver`
    - ROLE_QUEUE, LEAST_LOADED, ROUND_ROBIN (returns `CursorUpdate`), INITIATOR, FROM_FIELD; active users only; no writes
    - _Requirements: 8.4, 8.5, 8.8, 17.6_

  - [x] 7.3 Implement `PluginHandler`, `HandlerRegistry` and `EventRunner`
    - `PluginHandler` and `HandlerResult`, registry from Spring beans keyed by bean name (rely on Boot's override protection for duplicates), `HandlerCatalog` adapter
    - `EventRunner` for SET_FIELD, HANDLER, NOTIFY and WEBHOOK (`EVENT_DEFERRED`), `EVENT_FAILED` handling
    - _Requirements: 13.1, 13.2, 13.3, 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10_

  - [ ] 7.4 Implement Router guards and field, remarks and attachment validation
    - Instance not ended, action exists on the current step, role check, action `when` guard, assignee or escalation check
    - Readonly field writes rejected with 403, remarks and attachment rules rejected with 422, required fields rejected with 422
    - `RouterException` thrown before any write
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.14, 11.4, 11.5, 11.6, 11.7, 11.8_

  - [ ] 7.5 Implement Router handler run, onExit events and transition selection wiring
    - Run the Action handler and validate its changes, run onExit events, wire in `TransitionSelector` and Action `to` / next step resolution
    - Handler failures and invalid handler changes rejected before any write
    - _Requirements: 9.10, 9.11, 10.15, 12.1, 13.4, 13.5, 13.6, 13.7_

  - [ ] 7.6 Implement Router step entry loop, END handling and `Router.start`
    - Step entry loop: skipWhen with loop detection, AUTO steps, status resolution, onEnter events, assignee resolution with cursor update, due time, `stepEnteredAt`
    - END handling and `Router.start` for new instances
    - Assemble `ActionOutcome` (data, state, history entries, cursorUpdates)
    - _Requirements: 8.2, 8.3, 8.6, 9.12, 10.8, 10.9, 10.10, 10.11, 10.12, 10.13, 12.2, 12.3_

  - [ ] 7.7 Write property test for transition selection
    - **Property 9: Transition selection precedence**
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 19.5**

  - [ ] 7.8 Write property test for status resolution
    - **Property 22: Status resolution on step entry**
    - **Validates: Requirements 10.13, 10.14**

  - [ ]* 7.9 Write property test for assignee resolution
    - **Property 10: Assignee resolution**
    - **Validates: Requirements 8.4, 8.5, 8.8, 17.6**

  - [ ]* 7.10 Write property test for field rules
    - **Property 15: Field rules**
    - **Validates: Requirements 9.8, 9.9, 11.3, 11.4, 11.5, 11.7, 15.4**

  - [ ]* 7.11 Write property test for step events
    - **Property 17: Step events**
    - **Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7**

  - [ ]* 7.12 Write property test for skipped steps
    - **Property 18: Skipped steps**
    - **Validates: Requirements 10.8, 10.9, 10.10, 12.3**

  - [ ]* 7.13 Write property test for plugin handler changes
    - **Property 19: Plugin handler changes are validated**
    - **Validates: Requirements 9.10, 13.5, 13.6**

  - [ ]* 7.14 Write property test for start and step entry state
    - **Property 11: Start and step entry set initial state and due time**
    - **Validates: Requirements 8.2, 8.3, 10.13**

  - [ ] 7.15 Write property test for role gating
    - **Property 12: Role gating of actions**
    - **Validates: Requirements 9.3, 15.2, 15.3, 15.6, 19.2**

  - [ ] 7.16 Write property test for rejected requests
    - **Property 13: Rejected requests change nothing**
    - **Validates: Requirements 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.11, 9.14, 10.7, 10.10, 10.12, 10.15, 11.4, 11.5, 12.4, 13.4, 13.6, 13.7, 19.4**

  - [ ] 7.17 Write property test for routing invariants
    - **Property 14: Routing invariants over action sequences**
    - **Validates: Requirements 9.12, 19.1, 19.6, 19.7**

  - [ ] 7.18 Write property test for history continuity
    - **Property 16: History continuity, content and stability**
    - **Validates: Requirements 8.6, 9.12, 12.1, 12.2, 12.3, 12.5, 12.8, 12.9, 12.12, 19.8**

  - [ ] 7.19 Write property test for version pinning
    - **Property 7: Published versions are immutable and instances stay pinned**
    - **Validates: Requirements 5.2, 5.4, 5.5, 5.6, 19.3**

  - [ ]* 7.20 Write tests for handler registry and deferred events
    - Two beans with the same name fail context start with `BeanDefinitionOverrideException`; unknown handler gives 500; NOTIFY and WEBHOOK write `EVENT_DEFERRED`
    - _Requirements: 13.3, 13.4, 14.10_

- [ ] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Authentication and seeding
  - [ ] 9.1 Implement JWT auth, login and security config
    - `POST /api/auth/login` (BCrypt, dummy-hash timing on unknown user, identical 401 body, 400 validation naming the field), HS256 JWT with user id, roles, expiry, `JwtAuthFilter`, entry point 401, 403 handling, anonymous routes for login, public subset and form submit
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.7, 6.8, 17.5_

  - [ ]* 9.2 Write property test for JWT
    - **Property 21: JWT round trip and tamper resistance**
    - **Validates: Requirements 6.1, 6.4**

  - [ ] 9.3 Implement `SeedLoader`
    - `ApplicationRunner` at `@Order(1)`, plain password to BCrypt, pre-hashed pattern check, skip existing users, malformed seed exits non-zero naming the file, no path means no seeding
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.7, 17.8, 6.8_

  - [ ]* 9.4 Write auth and seed example tests
    - Identical 401 for unknown, wrong password and inactive user; 400 field validation; seed idempotence and hashing
    - _Requirements: 6.2, 6.3, 6.8, 17.2, 17.3, 17.4, 17.5, 17.7, 17.8_

- [ ] 10. Workflow service, endpoints, startup loading
  - [ ] 10.1 Implement `WorkflowService`
    - Transactional start and execute: load pinned definition, instance, cursors and open-task counts, call Router, persist in the specified order (record, instance with lock check, history, cursor updates, audit); map history write failure to 500 `HISTORY_WRITE_FAILED`
    - _Requirements: 8.1, 8.7, 9.12, 9.13, 9.14, 12.4, 5.4, 5.5_

  - [ ] 10.2 Implement instance endpoints
    - Action endpoint, history read endpoint (404 for unknown instance, 405 for modification), Instance_View with row visibility and pinned definition
    - _Requirements: 9.1, 12.5, 12.6, 12.7, 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7_

  - [ ] 10.3 Implement form submit endpoint
    - Public form without JWT, 401 for non-public without JWT, role check (403 with no Record stored), `startsWorkflow` atomic start, public responses contain only reference number and ids
    - _Requirements: 6.5, 6.6, 8.1, 11.2_

  - [ ] 10.4 Implement definition read and Public_Subset endpoints
    - `GET /api/apps/{app}/definition[?version=n]` with role-restricted forms, views and entities (404, 409, 401), `GET /api/public/apps/{app}` returning only public forms, their fields, options and labels
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7_

  - [ ]* 10.5 Write property test for public exposure
    - **Property 20: Public exposure is limited to the public subset**
    - **Validates: Requirements 11.2, 16.1, 16.4, 16.5**

  - [ ] 10.6 Implement `StartupLoader` with Auto_Publish
    - `ApplicationRunner` after `SeedLoader`; load files in sorted order, skip hash duplicates, log failures and continue; when `auto-publish` is true publish only newly stored DRAFTs through `DefinitionService.publish` with the system actor, leaving DRAFTs with violations and logging them
    - _Requirements: 1.5, 1.6, 1.10, 1.11, 1.12, 1.13, 12.10_

  - [ ]* 10.7 Write form submission authorization tests
    - Public form without JWT accepted; non-public without JWT 401; no `roles` accepted for any authenticated user; role holder accepted; non-holder 403 and no Record
    - _Requirements: 6.5, 6.6, 6.7_

  - [ ]* 10.8 Write configuration and auto-publish tests
    - `ApplicationContextRunner` cases for each invalid property naming it, multiple bad values all named, valid binding, `auto-publish` default true; auto-publish on and off, hash-skipped files not published
    - _Requirements: 1.12, 1.13, 20.1, 20.3, 20.4_

  - [ ]* 10.9 Write Testcontainers integration tests
    - Flyway on empty DB and failing migration; 50 concurrent creates yield unique references; concurrent actions give one 409; atomic form submit with forced failure; audit rollback; record create and read; listing, paging, filters and scopes; round-robin cursor unchanged on rejection and advanced on commit; startup with definitions directory; no migration for added field; history trigger rejects UPDATE and DELETE
    - Tests live under `skeleton/backend/src/test`
    - _Requirements: 7.1, 7.7, 7.8, 7.10, 7.11, 7.12, 7.13, 7.14, 7.17, 8.1, 8.4, 8.7, 9.13, 9.14, 12.7, 12.10, 12.11, 20.2, 20.5_

  - [ ]* 10.10 Write smoke tests
    - OpenAPI document lists records, definition, instance view and action endpoints; `/actuator/health` answers; source scan finds no status `enum`; error body shape
    - _Requirements: 7.18, 20.6, 20.7, 20.8_

- [ ] 11. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Pin exact library versions in `pom.xml`; do not use version ranges
- Persistence uses Spring JDBC (`JdbcClient`); the backend project lives in `skeleton/backend/`
- Each of the 22 correctness properties has its own `@Property(tries = 100)` sub-task tagged `Feature: skeleton-backend, Property N: <title>`
- Required property tests: 3.5, 3.6, 3.7 (Properties 4, 5, 6), 7.7 (Property 9), 7.8 (Property 22), 7.15 (Property 12), 7.16 (Property 13), 7.17 (Property 14), 7.18 (Property 16), 7.19 (Property 7); all other property tests are optional
- Router properties run against the in-memory port twins, no Spring context or database
- Property 5 does not cover Requirement 3.16; that rule is covered by the example test in 3.8

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "1.4", "2.1"] },
    { "id": 2, "tasks": ["2.2", "3.1", "5.1", "6.1", "6.3"] },
    { "id": 3, "tasks": ["2.3", "3.2", "3.3", "6.2", "7.1", "7.2", "7.3"] },
    { "id": 4, "tasks": ["3.4", "3.8", "5.2", "6.4", "7.4", "9.1"] },
    { "id": 5, "tasks": ["3.5", "3.6", "3.7", "5.3", "5.4", "5.5", "5.6", "6.5", "7.5", "7.7", "7.8", "7.9", "7.10", "7.15", "7.20", "9.2", "9.3"] },
    { "id": 6, "tasks": ["6.6", "7.6", "7.11", "7.13", "9.4"] },
    { "id": 7, "tasks": ["7.12", "7.16", "7.14", "7.17", "7.18", "7.19", "10.1"] },
    { "id": 8, "tasks": ["10.2", "10.3", "10.4", "10.6"] },
    { "id": 9, "tasks": ["10.5", "10.7", "10.8", "10.9", "10.10"] }
  ]
}
```
