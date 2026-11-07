# Requirements Document

## Introduction

The Skeleton Backend is the Spring Boot engine (Java package `dev.skeletonkey`) that runs an application described entirely by one versioned JSON App Definition. It loads definitions that conform to `schema/app-definition.schema.json`, validates them at publish time, exposes a metadata-driven records API backed by PostgreSQL JSONB, and routes workflow instances through steps via a single action pipeline, with version pinning and an audit history. It also provides an instance view endpoint, a definition read endpoint for the UI (including a public-safe subset), and startup seeding of users from a seed file.

Versioning applies to the WHOLE App_Definition document, identified by app code and version number. A Workflow_Instance pins to one document version plus a workflow code. The `workflow.version` property will be removed from `schema/app-definition.schema.json`; the Definition_Loader and the Validator must not depend on it. The schema will also be updated to add an optional `status` property to Steps and Transitions; this spec assumes that schema change.

Scope is the must-have tier only. Out of scope: Angular frontend, form and workflow builders, parallel/join execution, sub-workflows, SLA scheduler and escalation, notifications, webhooks, tenant overrides, file upload storage, and the two example apps. The schema keeps these constructs structurally valid, and the Validator checks them where they appear, but the Router does not execute them in this spec.

## Glossary

- **Skeleton_Backend**: The Spring Boot application defined by this spec.
- **App_Definition**: One JSON document conforming to `schema/app-definition.schema.json` that describes roles, entities, forms, views and workflows.
- **Definition_Loader**: The component that parses, schema-checks and stores App_Definitions.
- **Validator**: The publish-time component that checks structural and cross-reference rules of an App_Definition.
- **Validation_Report**: The list of violations produced by the Validator, each with a rule code, a JSON Pointer path and a message.
- **Definition_Version**: A stored whole App_Definition document identified by app code and version number, with status DRAFT or PUBLISHED and a Content_Hash.
- **Content_Hash**: A hash of the canonical JSON form of an App_Definition, where object key order and insignificant whitespace are ignored.
- **Records_API**: The REST endpoints that create, read, update, list and filter entity records using metadata.
- **Record**: One row of entity data stored in a JSONB column, identified by a UUID and a reference number.
- **Reference_Number**: A human-readable record number of the form `<referencePrefix>-<year>-<6-digit sequence>`, for example GRV-2026-000123.
- **Router**: The component that executes workflow actions through the Routing_Pipeline.
- **Routing_Pipeline**: The ordered stages for every action: permission check, field and remarks validation, handler run, transition selection, step entry, end detection.
- **Workflow_Instance**: A running workflow bound to one Record and pinned to one Definition_Version plus a workflow code.
- **Step**: A node of a workflow of type TASK, APPROVAL, AUTO or END within this spec's executable scope, with an optional `status` label applied on entry (see Requirement 10.14).
- **Action**: A button on a Step, with allowed roles, optional target step, optional status label, and remarks and attachment rules.
- **Transition**: A rule with from, to, optional on-action, optional condition, an optional `status` label and a priority that overrides an Action's target.
- **Expression**: A JSONLogic expression evaluated against the context `{ data, instance, user }`.
- **Status_Label**: A label declared in the workflow `statuses` list, with a category of OPEN, IN_PROGRESS, DONE or REJECTED.
- **History_Entry**: An immutable audit row recording one action or system event on a Workflow_Instance. Every History_Entry has exactly one kind from the following set.
- **START**: History_Entry kind written when a Workflow_Instance starts.
- **ACTION**: History_Entry kind written when an Action completes.
- **SKIPPED**: History_Entry kind written when a Step is skipped through `skipWhen`.
- **EVENT_DEFERRED**: History_Entry kind written when a NOTIFY or WEBHOOK event is not delivered because delivery is outside this release.
- **EVENT_FAILED**: History_Entry kind written when a Step event fails to evaluate or run.
- **EXPRESSION_ERROR**: History_Entry kind written when an Expression raises an error at run time in a Transition or `skipWhen`.
- **Plugin_Handler**: A Java bean implementing the handler interface, referenced by name in an Action, event or AUTO Step.
- **Principal**: The authenticated user (user id and role codes) or the anonymous public role.
- **Publish**: The operation that validates a Definition_Version and sets its status to PUBLISHED.
- **Auto_Publish**: The startup step that publishes newly stored DRAFT Definition_Versions, controlled by the property `skeletonkey.definitions.auto-publish` (default true).
- **Instance_View**: The response of the instance view endpoint, holding the current Step, status, available Actions of the caller and effective field rules.
- **Public_Subset**: The part of a PUBLISHED App_Definition that is needed to render public forms: each public form, its fields, option lists and labels, and nothing else.
- **Seed_File**: A JSON file at a configured path that lists users, their role codes, their credentials and an `active` flag.

## Requirements

### Requirement 1: Load App Definitions

**User Story:** As a platform operator, I want to load an App Definition from JSON, so that an app can be described as data without code changes.

#### Acceptance Criteria

1. WHEN a JSON document is submitted to the Definition_Loader, THE Definition_Loader SHALL check the document against `schema/app-definition.schema.json` using JSON Schema draft 2020-12.
2. IF the submitted document is not well-formed JSON, THEN THE Definition_Loader SHALL reject it with HTTP status 400, report the line number and column number of the first parse error, and store no Definition_Version.
3. IF the submitted document violates the JSON Schema, THEN THE Definition_Loader SHALL reject it with HTTP status 422, return a Validation_Report listing every schema violation with its JSON Pointer path (RFC 6901) and a message describing the violated constraint, and store no Definition_Version.
4. WHEN a schema-valid document is submitted through the API, THE Definition_Loader SHALL store it as a new DRAFT Definition_Version with its Content_Hash and a version number one greater than the highest existing version number for the app code declared in the document (1 when none exists), and return the identifier of the new Definition_Version.
5. WHEN the Skeleton_Backend starts and the configured startup directory contains definition files, THE Skeleton_Backend SHALL validate each file with the same rules as criteria 1 to 3, compute the Content_Hash of each schema-valid file, and store the file as a DRAFT Definition_Version only IF no stored Definition_Version has the same Content_Hash.
6. IF a definition file in the startup directory is not well-formed JSON or violates the JSON Schema, THEN THE Skeleton_Backend SHALL skip that file, record an error identifying the file name and the violations, and continue loading the remaining files and completing startup.
7. WHEN a stored Definition_Version is read, THE Definition_Loader SHALL return a document that is JSON-equal to the submitted document, where object key order and insignificant whitespace are ignored and array order, string values, and numeric values are preserved exactly.
8. IF a read request references a Definition_Version identifier that does not exist, THEN THE Definition_Loader SHALL respond with HTTP status 404 and return no document.
9. FOR ALL schema-valid App_Definitions, serializing then parsing then serializing SHALL produce a document that is JSON-equal to the original (round-trip property).
10. WHEN the Skeleton_Backend loads `examples/grievance.app.json`, THE Skeleton_Backend SHALL accept it with zero schema violations and store it as a DRAFT Definition_Version (subject to Auto_Publish under criterion 12).
11. WHEN the Skeleton_Backend is restarted twice with the same definition files in the startup directory, THE Skeleton_Backend SHALL create no additional Definition_Version at either restart.
12. WHERE `skeletonkey.definitions.auto-publish` is true (the default), WHEN startup loading completes, THE Skeleton_Backend SHALL run the Validator on each DRAFT Definition_Version newly stored in this startup run and publish it IF the Validator reports no violations; IF the Validator reports violations, THEN THE Skeleton_Backend SHALL leave that Definition_Version in DRAFT and log the violations with the file name and the Definition_Version identifier. Auto_Publish SHALL write the publish audit entry of Requirement 12.10 in the same way as a manual Publish, and SHALL NOT publish Definition_Versions skipped by the Content_Hash check of criterion 5.
13. WHERE `skeletonkey.definitions.auto-publish` is false, THE Skeleton_Backend SHALL leave every Definition_Version loaded at startup in DRAFT.

### Requirement 2: Validate Structure and References at Publish Time

**User Story:** As an app designer, I want the platform to reject broken definitions before they run, so that no live instance can reach an undefined state.

#### Acceptance Criteria

1. WHEN Publish is requested for a Definition_Version in DRAFT status, THE Validator SHALL evaluate every rule in Requirements 2 to 4 against the whole document and return a Validation_Report.
2. IF the Validation_Report contains one or more violations, THEN THE Skeleton_Backend SHALL leave the Definition_Version in DRAFT, leave its content unchanged, and return HTTP status 422 with the Validation_Report.
3. WHEN the Validation_Report contains zero violations, THE Skeleton_Backend SHALL set the Definition_Version status to PUBLISHED and return a success response.
4. THE Validator SHALL report every violation found in one pass instead of stopping at the first, and each violation in the Validation_Report SHALL identify the rule violated and the location of the offending element (collection and code, plus field code where applicable).
5. IF two roles, entities, forms, views or workflows share the same code within their collection, THEN THE Validator SHALL report one duplicate-code violation per duplicated code, naming the code and the collection.
6. IF more than one role has `public` set to true, THEN THE Validator SHALL report a violation naming each role code that has `public` set to true.
7. IF a field of type SELECT or MULTISELECT declares two options with the same value, THEN THE Validator SHALL report a violation naming the entity code, field code and duplicated option value.
8. IF a REFERENCE field names an entity code that does not exist in the Definition_Version, THEN THE Validator SHALL report a dangling-reference violation naming the field and the missing entity code.
9. IF an entity `titleField` does not name a field of that entity, THEN THE Validator SHALL report a violation naming the entity code and the invalid `titleField` value.
10. IF a form names an entity, a role, a field code or a `startsWorkflow` code that does not exist in the Definition_Version, or a field not belonging to the form's entity, THEN THE Validator SHALL report a dangling-reference violation naming the form code and the unresolved value.
11. IF a view names an entity or a role that does not exist, or a column or filter that is neither a field of the view's entity nor a permitted system column, THEN THE Validator SHALL report a dangling-reference violation naming the view code and the unresolved value.
12. IF a field declares `min` greater than `max`, THEN THE Validator SHALL report a violation naming the entity code, field code, and both values; WHEN `min` equals `max`, THE Validator SHALL NOT report a violation.
13. IF a field `pattern` is not a valid regular expression, THEN THE Validator SHALL report a violation naming the entity code and field code.
14. IF a workflow names an entity that does not exist, or a `startsWorkflow` form targets a workflow whose entity differs from the form's entity, THEN THE Validator SHALL report a violation naming the workflow code or form code involved.
15. IF Publish is requested for a Definition_Version whose status is not DRAFT, THEN THE Skeleton_Backend SHALL reject the request with an error indicating the invalid status and leave the Definition_Version status unchanged.

### Requirement 3: Validate Workflow Graph Rules

**User Story:** As an app designer, I want workflow graphs checked for soundness, so that every instance can progress to an end.

#### Acceptance Criteria

1. IF a workflow `startStep` does not name a Step of that workflow, THEN THE Validator SHALL report one violation identifying the workflow and the `startStep` value.
2. IF two or more Steps of one workflow share the same code, THEN THE Validator SHALL report one violation per duplicated code, identifying the workflow and the code. Comparison SHALL be case-sensitive.
3. IF a workflow has no Step of type END, THEN THE Validator SHALL report one violation identifying the workflow.
4. IF a Step cannot be reached from `startStep` by following Action targets, Transitions and the next-step-in-order fallback, THEN THE Validator SHALL report one unreachable-step violation per such Step, identifying the workflow and the Step code.
5. IF an Action `to` or a Transition `from` or `to` names a Step that does not exist in the workflow, THEN THE Validator SHALL report one dangling-transition violation per offending reference, identifying the workflow, the owning Action or Transition, and the missing Step code.
6. IF a Transition `on` names an Action code that does not exist on the `from` Step, THEN THE Validator SHALL report one violation per Transition, identifying the workflow, the `from` Step and the missing Action code.
7. IF an Action `status`, a Transition `status`, a Step `status`, a workflow `initialStatus` or an END `outcome` names a label not present in the workflow `statuses`, THEN THE Validator SHALL report one violation per offending reference, identifying the workflow, the referencing element and the missing label.
8. IF an Action or Step assignee names a role that does not exist, or an assignee `field` is not a USER field of the workflow entity, THEN THE Validator SHALL report one dangling-reference violation per offending assignee, identifying the workflow, the referencing element and the invalid role or field name.
9. IF a Step `fields` entry or a `skipWhen`, `when` or `copyFields` reference names a field that does not exist on the workflow entity, THEN THE Validator SHALL report one dangling-reference violation per offending reference, identifying the workflow, the referencing element and the missing field name.
10. IF a Step of type TASK or APPROVAL is not an END and has no Action whose target can be resolved by an Action `to`, a Transition or a following Step, THEN THE Validator SHALL report one dead-end violation per such Step, identifying the workflow and the Step code.
11. IF an Action `roles` entry names a role that does not exist, THEN THE Validator SHALL report one dangling-reference violation per offending entry, identifying the workflow, the Action and the missing role name.
12. IF two or more Transitions with the same `from` and `on` share the same `priority`, THEN THE Validator SHALL report one ambiguous-priority violation per conflicting group, identifying the workflow, the `from` Step, the `on` Action code and the shared priority.
13. IF a Step of type PARALLEL has a `branches` or `join` entry that names a Step not existing in the workflow, THEN THE Validator SHALL report one dangling-transition violation per missing Step.
14. IF a Step of type JOIN is named as `join` by zero PARALLEL Steps or by more than one PARALLEL Step, THEN THE Validator SHALL report one violation per such JOIN, identifying the workflow, the JOIN Step code and the number of naming PARALLEL Steps.
15. WHEN the app definition is published, IF a workflow contains a Step of type PARALLEL, JOIN or SUBWORKFLOW, THEN THE Validator SHALL report one unsupported-step violation per such Step, because the Router does not execute these types in this release, and SHALL reject the publish.
16. IF a Status_Label declared in a workflow `statuses` list is not settable by at least one Action `status`, Transition `status`, Step `status`, the workflow `initialStatus` (or the first OPEN status in declared order when `initialStatus` is absent) or END `outcome` of that workflow, THEN THE Validator SHALL report one violation with rule code UNSET_STATUS per such label, identifying the workflow and the label.

### Requirement 4: Validate Expressions and Handlers

**User Story:** As an app designer, I want expressions and handler names checked up front, so that routing never fails on a malformed condition.

#### Acceptance Criteria

1. WHEN an Expression appears in `skipWhen`, an Action `when`, a Transition `when` or an event `when`, THE Validator SHALL check that the Expression parses as JSONLogic using only supported operators.
2. IF an Expression does not parse as valid JSONLogic, THEN THE Validator SHALL report a violation containing the JSON Pointer path of the Expression and a message indicating a parse failure, and SHALL leave the app definition unmodified.
3. IF an Expression uses an operator that is not supported, THEN THE Validator SHALL report one violation per unsupported operator occurrence, each naming the operator and the JSON Pointer path of the Expression.
4. IF an Expression contains a `var` path whose first segment is not `data`, `instance` or `user`, THEN THE Validator SHALL report a violation naming the offending first segment and the JSON Pointer path of the Expression.
5. IF an Expression contains a `var` path beginning with `data.` that names a field not defined on the workflow entity, THEN THE Validator SHALL report a dangling-reference violation naming the missing field and the JSON Pointer path of the Expression.
6. IF an Action `handler` or AUTO Step `handler` names a bean not registered as a Plugin_Handler, THEN THE Validator SHALL report a violation naming the handler and the JSON Pointer path of the `handler` property.
7. IF an event of type SET_FIELD names a field that does not exist on the workflow entity, THEN THE Validator SHALL report a dangling-reference violation naming the missing field and the JSON Pointer path of the event.
8. WHEN validation of all Expressions and handlers completes, THE Validator SHALL return every violation found in a single result rather than stopping at the first violation.

### Requirement 5: Version Definitions

**User Story:** As an app designer, I want edits to create new versions of the whole app definition, so that running instances are not disturbed by later changes.

#### Acceptance Criteria

1. THE Skeleton_Backend SHALL identify a Definition_Version by the combination of app code and version number, where the combination is unique and the version number is a positive integer starting at 1.
2. WHEN a PUBLISHED Definition_Version is edited, THE Skeleton_Backend SHALL store the edit as a new DRAFT Definition_Version of the whole document with a version number one greater than the highest existing version number for that app code, and SHALL leave the edited PUBLISHED Definition_Version unchanged.
3. IF an update is requested against a PUBLISHED Definition_Version, THEN THE Skeleton_Backend SHALL reject the request with HTTP status 409 and leave the stored Definition_Version unchanged.
4. WHEN a new Workflow_Instance is started, THE Skeleton_Backend SHALL pin it to the highest-numbered PUBLISHED Definition_Version of the app at the moment the start request is processed, together with the workflow code, and ignore DRAFT versions regardless of their number.
5. WHILE a Workflow_Instance exists, THE Router SHALL evaluate all of its steps, actions, transitions and field rules against the workflow of its pinned Definition_Version and not against any other Definition_Version of the same app.
6. WHEN a newer Definition_Version is published, THE Skeleton_Backend SHALL leave the pinned Definition_Version of every existing Workflow_Instance unchanged.
7. IF a workflow start is requested and the app has no PUBLISHED Definition_Version, THEN THE Skeleton_Backend SHALL reject the request with HTTP status 409, create no Workflow_Instance, and return an error indicating that no published version exists.
8. IF an edit or start is requested for an app code or version number that does not exist, or for a workflow code that the Definition_Version does not contain, THEN THE Skeleton_Backend SHALL reject the request with an error indicating the missing Definition_Version or workflow and persist no changes.
9. THE Definition_Loader and the Validator SHALL operate without reading a `workflow.version` property, because that property will be removed from `schema/app-definition.schema.json`.

### Requirement 6: Authenticate and Authorize

**User Story:** As a platform operator, I want JWT login and role-based access, so that only permitted users can read and act on records.

#### Acceptance Criteria

1. WHEN valid credentials are submitted to the login endpoint, THE Skeleton_Backend SHALL return a JWT signed by the Skeleton_Backend containing the user id and all role codes assigned to the user, with an expiry time read from `application.properties`.
2. IF the login endpoint receives credentials that do not match a stored user, THEN THE Skeleton_Backend SHALL return HTTP status 401 with an identical response body for an unknown user name and for a wrong password, and SHALL NOT issue a JWT.
3. IF the login request has a missing or empty user name or password, or either exceeds 256 characters, THEN THE Skeleton_Backend SHALL return HTTP status 400 with an error indicating which field failed validation, and SHALL NOT issue a JWT.
4. IF a request to a protected endpoint carries a missing, expired or tampered JWT, THEN THE Skeleton_Backend SHALL return HTTP status 401 and SHALL NOT perform the requested action.
5. WHERE a form has `public` set to true, THE Skeleton_Backend SHALL accept submissions to that form from a caller with no JWT, treating the caller as holding only the public role.
6. IF a form has `public` set to false or has no `public` value, THEN THE Skeleton_Backend SHALL return HTTP status 401 for a submission from a caller with no JWT, SHALL accept a submission from any authenticated Principal when the form declares no `roles`, and SHALL, when the form declares `roles`, accept a submission only from a Principal holding at least one of those roles and return HTTP status 403 without storing a Record for an authenticated Principal holding none of those roles.
7. IF a Principal with a valid JWT lacks the role required by an endpoint, THEN THE Skeleton_Backend SHALL return HTTP status 403 and SHALL leave stored records unchanged.
8. THE Skeleton_Backend SHALL store passwords only as salted BCrypt hashes and SHALL NOT store or return the plain-text password in any response.

### Requirement 7: Dynamic Records API

**User Story:** As a client developer, I want generic endpoints driven by metadata, so that new entities need no new backend code.

#### Acceptance Criteria

1. WHEN a create request is received for an entity of a PUBLISHED app and the payload passes validation, THE Records_API SHALL store the Record in a JSONB column and return the created Record including its UUID and Reference_Number.
2. IF a create or update request targets an entity of an app that is not PUBLISHED, THEN THE Records_API SHALL reject the request with HTTP status 409 and store no Record.
3. THE Records_API SHALL validate each field value against its type, including NUMBER and DECIMAL bounds `min` and `max` (inclusive), TEXT `maxLength` (counted in characters) and `pattern`, EMAIL format, SELECT option membership, MULTISELECT option membership (every submitted value is an option, with no duplicates) and DATE (YYYY-MM-DD) and DATETIME (ISO-8601 with time zone offset) format.
4. IF a required field is missing or null on create, THEN THE Records_API SHALL reject the request with HTTP status 422, list every missing field code in the error body and store no Record.
5. IF the payload contains a key that is not a field code of the entity, THEN THE Records_API SHALL reject the request with HTTP status 422, list every unknown key in the error body and store no Record.
6. IF a field value does not satisfy its type or constraints, THEN THE Records_API SHALL reject the request with HTTP status 422, list every failing field with its code and the violated constraint, and leave stored data unchanged.
7. WHEN a Record is created, THE Records_API SHALL assign a UUID and a Reference_Number made from the entity `referencePrefix`, the current year and a sequence number that increases by one per prefix and year, so that no two Records share a Reference_Number, including under 50 concurrent create requests.
8. WHEN a Record is read by id, THE Records_API SHALL return the stored field values equal to those accepted on create or on the latest update.
9. IF a Record id does not exist or is not visible to the calling Principal, THEN THE Records_API SHALL respond with HTTP status 404.
10. WHEN a list request is received, THE Records_API SHALL return a page of Records with the page number, page size, total count and sort order, using a default page size of 20 and a maximum page size of 100.
11. IF a list request has a page size below 1 or above 100, or a page number below 1, THEN THE Records_API SHALL reject the request with HTTP status 400.
12. WHEN a list request names filter parameters, THE Records_API SHALL return only Records whose field values or system columns match every filter.
13. IF a filter or sort parameter names a field that is neither a field of the entity nor a permitted system column, THEN THE Records_API SHALL reject the request with HTTP status 400 and name the offending parameter in the error body.
14. WHEN a view `scope` is MINE, ASSIGNED_TO_ME or MY_ROLE_QUEUE, THE Records_API SHALL apply the corresponding row-level filter on the server for the calling Principal, and SHALL ignore any client-supplied filter that would widen the result beyond that scope.
15. IF an update request (PUT or PATCH) targets a Record that has a Workflow_Instance, THEN THE Records_API SHALL reject the request with HTTP status 409, indicating that the Record changes only through workflow actions, and leave the Record unchanged.
16. WHEN an update request (PUT or PATCH) targets a Record of an entity that has no workflow, THE Records_API SHALL apply the update subject to the field validation of criteria 3 to 6.
17. THE Records_API SHALL add no database migration when an App_Definition adds, removes or changes a field.
18. THE Skeleton_Backend SHALL publish an OpenAPI description of the Records_API, the definition endpoints, the instance view endpoint and the action endpoints.

### Requirement 8: Start Workflow Instances

**User Story:** As a citizen or staff member, I want submitting a form to start the workflow, so that my request is tracked from the first step.

#### Acceptance Criteria

1. WHEN a form with `startsWorkflow` is submitted successfully, THE Router SHALL create the Record and a Workflow_Instance pinned per Requirement 5 in one database transaction and return a response identifying both the Record and the Workflow_Instance.
2. WHEN a Workflow_Instance is started, THE Router SHALL set the current Step to the workflow `startStep` and the status to `initialStatus`, or to the first OPEN status in the order the statuses are declared in the App_Definition when `initialStatus` is absent.
3. WHEN a Workflow_Instance enters a Step with an `slaHours` value, THE Router SHALL store a due time equal to the entry time plus `slaHours` hours, where `slaHours` is a number greater than 0 and at most 8760.
4. WHEN a Workflow_Instance enters a Step, THE Router SHALL resolve the assignee using the Step assignee strategy: ROLE_QUEUE leaves the task unassigned and claimable by any user holding the role, LEAST_LOADED assigns the active user in the role with the fewest open tasks (ties broken by the lowest user identifier), ROUND_ROBIN assigns the next active user in the role in ascending user identifier order and wraps to the first user after the last, INITIATOR assigns the starting Principal, and FROM_FIELD assigns the user named in the referenced Record field.
5. IF no active user holds the role required by LEAST_LOADED or ROUND_ROBIN, THEN THE Router SHALL leave the task unassigned in the role queue and still complete the start of the Workflow_Instance.
6. WHEN a Workflow_Instance is started, THE Router SHALL write exactly one History_Entry of kind START within the same transaction, recording the starting Principal, the start Step, the initial status and the start time.
7. IF the transaction fails at any stage, THEN THE Router SHALL persist neither the Record, the Workflow_Instance nor any History_Entry, and SHALL return an error response indicating that the submission failed so the submitter can retry.
8. IF the FROM_FIELD strategy references a field that is empty or names a user who does not exist or is inactive, THEN THE Router SHALL leave the task unassigned in the role queue of the Step and still complete the start of the Workflow_Instance.

### Requirement 9: Execute Actions Through One Routing Pipeline

**User Story:** As a staff member, I want every button to follow the same checked path, so that results are predictable.

#### Acceptance Criteria

1. WHEN an action request names a Workflow_Instance and an Action code, THE Router SHALL run the Routing_Pipeline stages in this order: permission check, field and remarks validation, Plugin_Handler run, transition selection, step entry, end detection.
2. IF the Action code is not defined on the current Step of the pinned Definition_Version, THEN THE Router SHALL reject the request with HTTP status 409, return an error indicating the Action is not available on the current Step, and leave the Workflow_Instance unchanged.
3. IF the Principal holds no role listed in the Action `roles`, THEN THE Router SHALL reject the request with HTTP status 403, return an error indicating insufficient role, and leave the Workflow_Instance unchanged.
4. IF the Action has a `when` Expression that evaluates to false, THEN THE Router SHALL reject the request with HTTP status 409, return an error indicating the Action condition is not met, and leave the Workflow_Instance unchanged.
5. IF the Step assignee is a specific user AND the Principal is neither that user nor a holder of the Step's escalation role, THEN THE Router SHALL reject the request with HTTP status 403 and leave the Workflow_Instance unchanged.
6. IF the Action `remarks` is `required` and the request has no remarks or remarks containing only whitespace characters, THEN THE Router SHALL reject the request with HTTP status 422, return an error indicating remarks are required, and leave the Workflow_Instance unchanged.
7. IF the Action `attachment` is `required` and the request has no attachment reference, THEN THE Router SHALL reject the request with HTTP status 422, return an error indicating an attachment is required, and leave the Workflow_Instance unchanged.
8. IF the request carries a value for a field that the current Step does not mark `optional` or `required`, THEN THE Router SHALL reject the whole request with HTTP status 403, return an error naming each rejected field, and leave the Workflow_Instance unchanged.
9. IF a field the current Step marks `required` is empty after applying the request values, THEN THE Router SHALL reject the request with HTTP status 422, return an error naming every empty required field, and leave the Workflow_Instance unchanged.
10. WHERE an Action names a `handler`, THE Router SHALL invoke the Plugin_Handler exactly once per accepted request, after all validation stages pass and before transition selection.
11. IF a Plugin_Handler throws an exception, THEN THE Router SHALL roll back the whole action, return HTTP status 500 with an error identifier, and leave the Workflow_Instance and its History unchanged.
12. WHEN the pipeline completes, THE Router SHALL commit field changes, the new Step, the new status, the due time, the assignee, exactly one History_Entry of kind ACTION and any SKIPPED, EXPRESSION_ERROR, EVENT_DEFERRED or EVENT_FAILED entries raised during the action in one database transaction, so that either all changes are persisted or none are.
13. IF two action requests for the same Workflow_Instance arrive concurrently, THEN THE Router SHALL apply exactly one request and reject the other with HTTP status 409 and an error indicating a concurrent modification, by using optimistic locking on the instance.
14. IF the pipeline fails at any stage, THEN THE Router SHALL add no History_Entry and persist no field, Step, status, due time or assignee change.

### Requirement 10: Select Transitions and Next Step

**User Story:** As an app designer, I want conditional routing defined as data, so that the path depends on record content.

#### Acceptance Criteria

1. WHEN an Action completes, THE Router SHALL select the target Step by this precedence: the matching Transition with the lowest priority value, otherwise the Action `to`, otherwise the next Step in declared order.
2. WHEN the Router evaluates a Transition, THE Router SHALL treat the Transition as matching only if its `from` equals the current Step, its `on` is absent or equals the Action code, and its `when` is absent or evaluates to true against the context `{ data, instance, user }`.
3. WHERE two or more matching Transitions have different priorities, THE Router SHALL choose the one with the lowest priority number.
4. WHERE two or more matching Transitions share the lowest priority number, THE Router SHALL choose the one declared first in the Transition list.
5. WHERE the priority of a Transition is absent, THE Router SHALL treat the priority as 100.
6. IF an Expression evaluation raises an error at run time for a Transition, THEN THE Router SHALL treat the Transition as not matching, continue evaluating the remaining Transitions, and write a separate History_Entry of kind EXPRESSION_ERROR identifying the Transition and the Expression error.
7. IF no Transition matches, the Action has no `to`, and the current Step is the last Step in declared order, THEN THE Router SHALL reject the action with HTTP status 422 and leave the Workflow_Instance unchanged.
8. WHEN the target Step has a `skipWhen` Expression that evaluates to true, THE Router SHALL write a History_Entry of kind SKIPPED for that Step and route onward from it as if an Action with no code had completed.
9. IF a `skipWhen` Expression evaluation raises an error at run time, THEN THE Router SHALL treat the Step as not skipped, enter the Step, and write a separate History_Entry of kind EXPRESSION_ERROR identifying the Step and the Expression error.
10. IF a chain of skipped Steps would revisit a Step already skipped within the same action, THEN THE Router SHALL reject the action with HTTP status 500 and leave the Workflow_Instance and its History unchanged.
11. WHEN the target Step is of type AUTO, THE Router SHALL invoke its Plugin_Handler on entry and then route onward from it as if an Action with no code had completed.
12. IF the Plugin_Handler of an AUTO Step fails, THEN THE Router SHALL reject the action with HTTP status 500 and leave the Workflow_Instance unchanged.
13. WHEN the target Step is of type END, THE Router SHALL set the status to the Status_Label named by the END `outcome`, mark the Workflow_Instance as ended, clear the assignee and clear the due time.
14. WHEN a Workflow_Instance enters a target Step that is not END, THE Router SHALL determine the instance status by this precedence: the `status` of the selected Transition, otherwise the `status` of the Action, otherwise the `status` of the target Step, otherwise the current status is kept. A skipped Step applies no `status` of its own; the precedence is applied again for the Step that is finally entered, using the Transition selected for that move. WHEN the target Step is END, the status comes from the END `outcome` as in criterion 13.
15. WHEN a Workflow_Instance has ended, THE Router SHALL reject further action requests with HTTP status 409 and leave the Workflow_Instance unchanged.

### Requirement 11: Field Rules per Step

**User Story:** As an app designer, I want per-step field rules, so that each stage presents only the fields it needs and public callers never see internal data.

#### Acceptance Criteria

1. WHILE a field is marked `hidden` by a Step, THE Skeleton_Backend SHALL treat the field as not part of that Step's form presentation, and SHALL NOT treat `hidden` as an access-control rule for authenticated roles.
2. THE Skeleton_Backend SHALL never return to the public role, in any response, a field that is not exposed by a public form or not part of the Public_Subset.
3. WHILE a field is not listed in the current Step `fields`, THE Router SHALL treat the field as `readonly`.
4. IF a write request targets a field that is `readonly`, THEN THE Router SHALL reject the entire request with HTTP status 403, return an error indicating the names of the readonly fields, and leave the Record unchanged.
5. WHEN an Action is submitted and a field marked `required` for the current Step has an absent value, a null value, an empty string, or a string containing only whitespace, THE Router SHALL reject the Action with an error indicating the names of the missing fields, keep the Record in its current Step, and leave the Record unchanged.
6. WHEN an Action is submitted and a field marked `optional` for the current Step has a value that is present and valid for the field type, THE Router SHALL accept and store the value.
7. WHEN an Action is submitted and a field marked `optional` for the current Step is absent, THE Router SHALL accept the Action without that field.
8. IF an Action is submitted and a field marked `optional` has a value that is present but invalid for the field type, THEN THE Router SHALL reject the Action with an error indicating the field name and the validation failure, and leave the Record unchanged.

### Requirement 12: Record History and Audit

**User Story:** As an auditor, I want an immutable trail of every action, so that I can reconstruct how a record reached its state.

#### Acceptance Criteria

1. WHEN an action completes, THE Router SHALL write a History_Entry of kind ACTION holding Action code, from-Step, to-Step, from-status, to-status, actor id, remarks (0 to 2,000 characters), attachment reference (absent or exactly one), event time in UTC, and time spent in the from-Step in milliseconds (0 or greater).
2. WHEN a Workflow_Instance starts, THE Router SHALL write a History_Entry of kind START holding the initial Step as to-Step, the initial status as to-status, actor id and event time in UTC, with from-Step and from-status empty.
3. WHEN a Step is skipped, THE Router SHALL write a History_Entry of kind SKIPPED holding the skipped Step as from-Step, the next Step as to-Step, from-status, to-status, actor id and event time in UTC, and time spent in the from-Step in milliseconds.
4. IF writing a History_Entry fails, THEN THE Router SHALL roll back the action, start or skip that triggered it, leave the Record and Workflow_Instance in their prior state, and return an error indicating the history write failed.
5. WHEN a client requests the History_Entries of a Workflow_Instance through the read endpoint, THE Skeleton_Backend SHALL return all entries ordered by event time ascending, with ties broken by the order in which the entries were written.
6. IF the requested Workflow_Instance does not exist, THEN THE Skeleton_Backend SHALL return an error indicating the instance was not found and return no entries.
7. THE Skeleton_Backend SHALL provide no operation that updates or deletes a History_Entry, and SHALL reject any such request with an error indicating the operation is not supported, leaving the entry unchanged.
8. WHEN the History_Entries of a Workflow_Instance are read repeatedly with no new entries written between reads, THE Skeleton_Backend SHALL return identical entries in identical order on every read.
9. FOR ALL Workflow_Instances, considering only the History_Entries of kind START, ACTION and SKIPPED in order, the to-Step of each such entry SHALL equal the from-Step of the following such entry (continuity property); History_Entries of kind EVENT_DEFERRED, EVENT_FAILED and EXPRESSION_ERROR SHALL be excluded from this chain.
10. WHEN a Principal creates or updates a Record or publishes a Definition_Version, or WHEN Auto_Publish publishes a Definition_Version (actor id: a reserved system actor identifier), THE Skeleton_Backend SHALL write an audit log entry holding actor id, operation (create, update or publish), target identifier and event time in UTC, in the same transaction as the operation.
11. IF writing an audit log entry fails, THEN THE Skeleton_Backend SHALL roll back the operation and return an error indicating the audit write failed.
12. THE Skeleton_Backend SHALL store and return all time values in UTC using ISO 8601 format with millisecond precision.

### Requirement 13: Plugin Handler Interface

**User Story:** As an app developer, I want a Java hook for custom logic, so that an app can extend the engine without forking it.

#### Acceptance Criteria

1. THE Skeleton_Backend SHALL define a Plugin_Handler interface that receives the Record data, the Workflow_Instance state, the Principal and the Action code, and returns either no field changes or a set of field changes expressed as field name and new value pairs.
2. WHEN the Skeleton_Backend starts, THE Skeleton_Backend SHALL register every Spring bean implementing the Plugin_Handler interface under its bean name, so that each registered handler is resolvable by that name once startup completes.
3. IF two Spring beans implementing the Plugin_Handler interface resolve to the same bean name, THEN THE Skeleton_Backend SHALL fail startup with an error identifying the duplicate bean name.
4. WHEN an action references a Plugin_Handler bean name that is not registered, THE Router SHALL roll back the action, preserve the Record data and Workflow_Instance state unchanged, and return HTTP status 500 with an error identifier indicating an unknown handler.
5. WHEN a Plugin_Handler returns field changes, THE Router SHALL apply them to the Record data and validate the resulting Record data against the entity field definitions (field existence, type, required and length constraints) before commit.
6. IF the field changes returned by a Plugin_Handler fail validation, THEN THE Router SHALL roll back the action, preserve the Record data and Workflow_Instance state as they were before the action, and return HTTP status 500 with an error identifier.
7. IF a Plugin_Handler throws an exception, THEN THE Router SHALL roll back the action, preserve the Record data and Workflow_Instance state as they were before the action, and return HTTP status 500 with an error identifier.

### Requirement 14: Step Events

**User Story:** As an app designer, I want simple events on step entry and exit, so that fields can be set automatically.

#### Acceptance Criteria

1. WHEN a Workflow_Instance exits a Step, THE Router SHALL run each event in the `onExit` list of that Step sequentially, in the order declared, before running any `onEnter` event of the next Step.
2. WHEN a Workflow_Instance enters a Step, THE Router SHALL run each event in the `onEnter` list of that Step sequentially, in the order declared.
3. IF an event has a `when` Expression that evaluates to false, THEN THE Router SHALL skip that event and continue with the next event in the list.
4. IF an event has a `when` Expression that cannot be evaluated or does not return a boolean, THEN THE Router SHALL skip that event, write a separate History_Entry of kind EVENT_FAILED naming the event and the evaluation failure, and continue with the next event in the list.
5. WHEN an event of type SET_FIELD runs and its value is not an object, THE Router SHALL set the named field to that literal value.
6. WHEN an event of type SET_FIELD runs and its value is an object, THE Router SHALL evaluate the value as a JSONLogic Expression against the current Workflow_Instance data and set the named field to the result.
7. IF a SET_FIELD event names a field that is not defined in the app definition, or its JSONLogic Expression fails to evaluate, THEN THE Router SHALL leave all field values unchanged by that event, write a separate History_Entry of kind EVENT_FAILED naming the event and the failure, and continue with the next event in the list.
8. WHEN an event of type HANDLER runs, THE Router SHALL invoke the named Plugin_Handler once.
9. IF the named Plugin_Handler is not registered or fails during invocation, THEN THE Router SHALL write a separate History_Entry of kind EVENT_FAILED naming the event, the handler and the failure, and continue with the next event in the list.
10. WHEN an event of type NOTIFY or WEBHOOK runs, THE Router SHALL write one History_Entry of kind EVENT_DEFERRED naming the event, without attempting delivery, because delivery is outside this release.

### Requirement 15: Instance View Endpoint

**User Story:** As a UI developer, I want one endpoint that describes the state of an instance for the caller, so that the UI can render the right buttons and fields.

#### Acceptance Criteria

1. WHEN an instance view request is received for a Workflow_Instance visible to the Principal, THE Skeleton_Backend SHALL return an Instance_View holding the current Step, the status, the available Actions of the Principal and the effective field rules of the current Step.
2. THE Skeleton_Backend SHALL include in the available Actions only those Actions of the current Step whose `roles` contain at least one role of the Principal and whose `when` Expression is absent or evaluates to true against the context `{ data, instance, user }`.
3. IF the `when` Expression of an Action raises an error at run time during an instance view request, THEN THE Skeleton_Backend SHALL omit that Action from the available Actions and return the Instance_View without writing a History_Entry.
4. THE Skeleton_Backend SHALL report, for each field of the workflow entity, the effective field rule of the current Step as one of readonly, optional, required or hidden, where a field not listed in the Step `fields` is reported as readonly.
5. IF the Workflow_Instance does not exist or is not visible to the Principal, THEN THE Skeleton_Backend SHALL respond with HTTP status 404 and return no Instance_View.
6. WHILE a Workflow_Instance has ended, THE Skeleton_Backend SHALL return an Instance_View with an empty list of available Actions.
7. THE Skeleton_Backend SHALL evaluate the Instance_View against the pinned Definition_Version of the Workflow_Instance.

### Requirement 16: Definition Read Endpoint for the UI

**User Story:** As a UI developer, I want to read the published app definition, so that the UI can render forms and views, including public forms without login.

#### Acceptance Criteria

1. WHEN an authenticated Principal requests the App_Definition of an app, THE Skeleton_Backend SHALL return the highest-numbered PUBLISHED Definition_Version restricted to the forms and views that either declare no `roles` or declare `roles` containing at least one role of the Principal, plus the entities those forms and views reference. A form or view that declares no `roles` is available to any authenticated Principal. A form with `public` set to true remains governed by criterion 4 for callers with no JWT.
2. WHEN an authenticated Principal requests a specific version number of an app, THE Skeleton_Backend SHALL return that Definition_Version restricted as in criterion 1 IF its status is PUBLISHED.
3. IF the requested app code or version number does not exist, THEN THE Skeleton_Backend SHALL respond with HTTP status 404; IF the requested Definition_Version is not PUBLISHED, THEN THE Skeleton_Backend SHALL respond with HTTP status 409.
4. WHEN a caller with no JWT requests the Public_Subset of an app, THE Skeleton_Backend SHALL return, for each form with `public` set to true in the highest-numbered PUBLISHED Definition_Version, the form, its fields, the option lists of those fields and their labels, without requiring authentication.
5. THE Skeleton_Backend SHALL exclude workflows, roles, handlers, and every field not exposed by a public form from the Public_Subset.
6. IF the app has no PUBLISHED Definition_Version, THEN THE Skeleton_Backend SHALL respond to a Public_Subset request with HTTP status 404.
7. IF a request with an expired or tampered JWT is made to the authenticated definition read endpoint, THEN THE Skeleton_Backend SHALL respond with HTTP status 401 and return no definition.

### Requirement 17: Seed Users and Roles

**User Story:** As a platform operator, I want users and roles loaded from a seed file at startup, so that a fresh install can be logged into and can assign tasks.

#### Acceptance Criteria

1. WHEN the Skeleton_Backend starts and a Seed_File path is configured, THE Skeleton_Backend SHALL load each user in the Seed_File with its user name, role codes and `active` flag.
2. WHEN a seed user has a plain-text password, THE Skeleton_Backend SHALL store the password as a salted BCrypt hash; WHEN a seed user has a pre-hashed BCrypt password, THE Skeleton_Backend SHALL store that hash as given.
3. WHEN a seed user has the same user name as an existing user, THE Skeleton_Backend SHALL leave the existing user unchanged and create no duplicate.
4. WHEN the Skeleton_Backend is restarted with the same Seed_File, THE Skeleton_Backend SHALL create no additional users.
5. IF a user with `active` set to false attempts to log in, THEN THE Skeleton_Backend SHALL return HTTP status 401 with the same response body as for a wrong password and SHALL NOT issue a JWT.
6. THE Router SHALL skip users with `active` set to false in every assignee strategy.
7. IF the Seed_File is not well-formed JSON or does not match the seed structure, THEN THE Skeleton_Backend SHALL stop startup with a non-zero exit code and a message naming the Seed_File.
8. WHERE no Seed_File path is configured, THE Skeleton_Backend SHALL complete startup without seeding.

### Requirement 18: Property-Based Tests for the Validator

**User Story:** As a maintainer, I want jqwik properties on the Validator, so that its accept and reject decisions are checked across many generated definitions.

#### Acceptance Criteria

1. THE Skeleton_Backend SHALL include a jqwik generator that produces schema-valid App_Definitions in which every field type, every supported step type and every assignee strategy defined by the schema appears in at least one generated value across a run of 100 tries, and each generated App_Definition contains between 1 and 20 roles, 1 and 10 entities and 2 and 30 steps.
2. THE Skeleton_Backend SHALL include a jqwik property verifying that the Validator returns an empty Validation_Report for every App_Definition produced by the generator in criterion 1.
3. THE Skeleton_Backend SHALL include a jqwik generator that takes a valid App_Definition and applies exactly one defect chosen from the following ten defects: removing the start step, removing every END step, adding an unreachable step, pointing an Action `to` at a missing step, pointing a Transition at a missing step, duplicating a code, naming a missing role, naming a missing entity, naming a missing field in an Expression, and using an unsupported operator, such that all ten defects are selected at least once across a run of 100 tries and the mutated App_Definition remains schema-valid.
4. THE Skeleton_Backend SHALL include a jqwik property verifying that the Validator returns a Validation_Report with at least one entry whose rule code equals the rule code of the injected defect, for every App_Definition produced by the generator in criterion 3.
5. THE Skeleton_Backend SHALL include a jqwik property verifying that validating the same App_Definition twice returns Validation_Reports with equal entries in equal order (idempotence).
6. THE Skeleton_Backend SHALL include a jqwik property verifying that, for any generated App_Definition (valid or mutated) whose `roles` and `entities` entries are reordered by a generated permutation with the `steps` order unchanged, the reordered App_Definition yields an empty Validation_Report if and only if the original yields an empty Validation_Report.
7. THE Skeleton_Backend SHALL include a jqwik property verifying that the Validator returns a Validation_Report without throwing any exception for every schema-valid App_Definition, including App_Definitions whose Expression objects are arbitrary, with nesting depth from 0 to 10 and any combination of operators, field references and literal values.
8. THE Skeleton_Backend SHALL include a jqwik property verifying the round trip of Requirement 1.9 for every App_Definition produced by the generator in criterion 1.
9. WHEN the build's test task is executed, THE Skeleton_Backend SHALL run each jqwik property defined for criteria 2 and 4 to 8 for at least 100 tries and SHALL report the test task as failed IF any try falsifies a property.
10. IF a jqwik property is falsified, THEN THE Skeleton_Backend SHALL report the failing test with the shrunk counterexample App_Definition and the random seed so the failure can be reproduced.

### Requirement 19: Property-Based Tests for the Router

**User Story:** As a maintainer, I want jqwik properties on the Router, so that no sequence of actions can break the engine's guarantees.

#### Acceptance Criteria

1. THE Skeleton_Backend SHALL include a jqwik property verifying that, for any published workflow and any generated sequence of 1 to 50 action requests (valid or invalid), the current Step of the Workflow_Instance after each request is a Step declared in its pinned Definition_Version.
2. THE Skeleton_Backend SHALL include a jqwik property verifying that, for any generated Principal holding 0 to 10 roles and any generated Action, an action request is accepted if and only if the Principal holds at least one role listed in the Action `roles`.
3. THE Skeleton_Backend SHALL include a jqwik property verifying that a Workflow_Instance pinned to a Definition_Version produces identical routing outcomes (resulting Step and status) for the same subsequent action sequence before and after 1 to 5 later Definition_Versions of the app are published.
4. THE Skeleton_Backend SHALL include a jqwik property verifying that, for any rejected action request, the Record data, current Step, status and History_Entries of the Workflow_Instance are equal to their values immediately before the request, and the response indicates the rejection.
5. THE Skeleton_Backend SHALL include a jqwik property verifying that, for any generated set of 0 to 20 matching Transitions, the Router selects the Transition with the lowest priority value, and, WHEN no Transition matches, selects the Action `to`, and, WHEN the Action `to` is absent, selects the next Step in declared order.
6. THE Skeleton_Backend SHALL include a jqwik property verifying that every status set on a Workflow_Instance during any generated action sequence is a label declared in the `statuses` of its pinned workflow.
7. THE Skeleton_Backend SHALL include a jqwik property verifying that, after each request in any generated action sequence of 1 to 50 requests on a Validator-approved workflow, the Workflow_Instance either is at a non-END Step with at least one executable Action or has ended.
8. THE Skeleton_Backend SHALL include a jqwik property verifying that the History_Entry continuity property of Requirement 12.9, over the entries of kind START, ACTION and SKIPPED only, holds after any generated action sequence of 1 to 50 requests.
9. WHEN the build's test task runs, THE Skeleton_Backend SHALL execute every Router property against an in-memory test double of the persistence layer, with at least 100 tries per property, and SHALL fail the test task IF any property is falsified.

### Requirement 20: Configuration and Packaging

**User Story:** As a developer, I want a simple, runnable backend, so that I can start the skeleton with one command.

#### Acceptance Criteria

1. WHEN the Skeleton_Backend starts, THE Skeleton_Backend SHALL read the following properties from `application.properties`: the database URL (`spring.datasource.url`, with the standard `spring.datasource.*` properties for credentials) and the application properties under the prefix `skeletonkey.*`: JWT secret, JWT expiry in seconds, startup definition directory, seed file path, maximum page size and `skeletonkey.definitions.auto-publish` (boolean, default true when absent).
2. WHEN the Skeleton_Backend starts, THE Skeleton_Backend SHALL apply all pending versioned database migrations, in ascending version order, to create and update the tables that hold definitions, records, instances, history, audit log and users.
3. IF a required configuration property is missing or empty at startup, THEN THE Skeleton_Backend SHALL stop startup with a non-zero exit code and a message naming each missing property; the seed file path is optional and is excluded from this criterion.
4. IF a configuration property has an invalid value at startup (JWT expiry or maximum page size not a positive integer, JWT secret shorter than 32 characters, or `skeletonkey.definitions.auto-publish` not equal to true or false), THEN THE Skeleton_Backend SHALL stop startup with a non-zero exit code and a message naming the property and the reason it is invalid.
5. IF a database migration fails at startup, THEN THE Skeleton_Backend SHALL stop startup with a non-zero exit code and a message naming the failed migration, and SHALL leave previously applied migrations intact.
6. IF a request fails, THEN THE Skeleton_Backend SHALL return a JSON error body containing the HTTP status, an error code, a message, a request identifier and, for validation failures, a list of field-level violations each naming the field and the rule violated.
7. WHEN a user runs `docker-compose up` in the project root with Docker available, THE Skeleton_Backend SHALL start together with a PostgreSQL service and accept requests.
8. THE Skeleton_Backend SHALL contain no Java enum type that represents workflow status values.
