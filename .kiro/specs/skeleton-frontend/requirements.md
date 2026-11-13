# Requirements Document

## Introduction

The Skeleton Frontend is an Angular single-page application in `skeleton/frontend/` that renders any published App_Definition served by the Skeleton Backend. It contains no app-specific fields, statuses or labels: every form, table, board, field rule, action and status colour comes from the definition and from the data returned by the backend.

One built image serves several apps. A Runtime_Config file (`assets/config.json`) is read at startup and maps host names to a default app code, so different subdomains open different apps. The file can be replaced in the Nginx container without rebuilding the image. Routes stay `/app/:app/...`.

Scope is must-have only. Screens: public form page, login and app shell, view renderers (TABLE, KANBAN, MY_TASKS), record detail with workflow actions and history, and an admin area (definitions, JSON editor with validator report and Publish, minimal form builder for entity fields and form layout). Detailed visual polish is deferred.

The Frontend depends on backend capabilities that a separate backend spec delivers first (see Requirement 17). The Frontend implements no fallback, placeholder or workaround for any of them.

The backend API used (from `skeleton/backend` controllers, `.kiro/specs/skeleton-backend/design.md` and the Backend_Dependencies):

| Purpose | Endpoint |
|---|---|
| Login | `POST /api/auth/login` returns `{ token, tokenType, expiresInSeconds, userId, displayName, roles[] }` |
| Current user | `GET /api/auth/me` returns `{ userId, displayName, roles[] }` |
| Public subset | `GET /api/public/apps/{app}` (anonymous), includes the app `branding` object |
| Restricted definition | `GET /api/apps/{app}/definition[?version=n]`, includes the app `branding` object |
| Form submit | `POST /api/apps/{app}/forms/{form}/submit` returns `{ recordId, referenceNo, instanceId }` |
| Records | `GET/POST /api/apps/{app}/entities/{entity}/records`, `GET/PUT/PATCH .../records/{id}`; list returns `{ items[], page, size, total, sort }`, params `page`, `size`, `sort` (`field,asc\|desc`), `view`, other params are equality filters. Each list item and each record view includes `instanceId`, `step`, `stepLabel`, `status`, `statusColor`, `assignee`, `dueAt` and `ended` |
| Instance | `GET /api/instances/{id}` returns `{ instanceId, recordId, step, status, ended, dueAt, assignee, actions[{code,label,style,confirm,remarks,attachment}], fieldRules{} }`; `POST /api/instances/{id}/actions/{code}` with `{ fields, remarks, attachment }`; `GET /api/instances/{id}/history` |
| Admin: app codes | `GET /api/definitions/apps` returns the app codes that have definitions |
| Admin: versions | `GET /api/apps/{app}/definitions` returns the version references (`id`, `version`, `status`, `contentHash`, timestamps) of an app |
| Admin: validate | `POST /api/definitions/validate` returns the Validator_Report for a body without storing a version |
| Admin: store | `POST /api/definitions`, `PUT /api/definitions/{id}`, `POST /api/apps/{app}/definitions/{base}/edit`, `GET /api/definitions/{id}`, `GET /api/definitions/{id}/info`, `POST /api/definitions/{id}/publish` |
| Errors | `ApiError { status, code, message, requestId, violations[{field, rule, message?}], line?, column? }` |

## Glossary

- **Frontend**: The Angular application in `skeleton/frontend/`.
- **Backend**: The Skeleton Backend in `skeleton/backend/`.
- **Backend_Dependency**: A Backend capability that the Backend delivers before the Frontend is built and that the Frontend uses without any fallback; the list is in Requirement 17.
- **App_Definition**: The versioned JSON document described by `schema/app-definition.schema.json`.
- **Public_Subset**: The anonymous projection of an App_Definition returned by `GET /api/public/apps/{app}`: the `app` object with branding, public forms and the fields they list.
- **Restricted_Definition**: The role-filtered App_Definition projection returned by `GET /api/apps/{app}/definition`: app info with branding, roles, entities, forms, views and a workflow presentation subset (statuses with colours, steps with labels and types).
- **Record_Summary**: A record list item or record view, including `instanceId`, `step`, `stepLabel`, `status`, `statusColor`, `assignee`, `dueAt` and `ended`.
- **Runtime_Config**: The JSON file `assets/config.json`, fetched by the Frontend at startup, holding the Backend base path and the Host_Mapping.
- **Host_Mapping**: The object in the Runtime_Config that maps a lower-case host name (without port) to a default app code.
- **Default_App**: The app code that the Host_Mapping assigns to the host name of the current page.
- **Session**: The login result (token, user id, display name, roles) held in sessionStorage.
- **Shell**: The authenticated layout containing the header with branding and the navigation menu.
- **Dynamic_Form**: The `DynamicFormComponent` that builds a reactive form from a list of field definitions and per-field rules.
- **Field_Rule**: One of `hidden`, `readonly`, `optional`, `required`, as returned in `fieldRules` of the Instance_View.
- **Field_Widgets**: The `FIELD_WIDGETS` injection token through which apps register custom field widgets.
- **Dynamic_Table**: The `DynamicTableComponent` rendering a TABLE view.
- **Kanban_Board**: The `KanbanComponent` rendering a KANBAN view.
- **My_Tasks**: The inbox rendering of a MY_TASKS view.
- **Record_Detail**: The `RecordDetailComponent` showing one record, its Instance_View actions and its history.
- **Instance_View**: The backend's per-instance state for the caller (step, status, actions, field rules).
- **Api_Error**: The backend's error JSON shape.
- **Violation**: One entry of `Api_Error.violations`, with `field`, `rule` and an optional `message`.
- **Error_Mapper**: The pure function that assigns Violations to form controls.
- **SLA_Warning**: A visual indicator on an item whose `dueAt` is within the warning window or already past.
- **Warning_Window**: The period before `dueAt` in which the SLA_Warning shows as "due soon"; 24 hours.
- **Platform_Admin**: A user whose roles include the admin role code `PLATFORM_ADMIN`.
- **Admin_Area**: The screens under `/admin`, available to a Platform_Admin only.
- **JSON_Editor**: The admin text editor for an App_Definition document.
- **Validator_Report**: The list of Violations returned when a definition is validated, rejected on save, or fails to publish.
- **Form_Builder**: The admin tool that edits an entity's fields and a form's layout with drag and drop and writes App_Definition JSON.
- **Nginx_Container**: The container that serves the production build and proxies `/api` to the Backend.

## Requirements

### Requirement 1: Project setup and conventions

**User Story:** As a developer, I want a reproducible Angular project that follows the repository conventions, so that the skeleton builds the same on Windows and Linux.

#### Acceptance Criteria

1. THE Frontend SHALL reside in `skeleton/frontend/` and use the latest stable Angular release with standalone components and signals, with no NgModule-based components.
2. THE Frontend SHALL use NG-ZORRO as its only UI component library.
3. THE Frontend SHALL pin every dependency and devDependency in `package.json` to an exact version, with no `^` or `~` range, and commit the lockfile.
4. THE Frontend SHALL provide the npm scripts `start`, `build`, `test` and `lint`, each runnable unchanged in Windows CMD and in a Linux shell.
5. THE Frontend SHALL write every source file as UTF-8 without BOM with LF line endings.
6. THE Frontend SHALL read the Backend base path from the Runtime_Config (Requirement 21), defaulting to the relative path `/api` when the Runtime_Config omits it.
7. WHERE the development server runs, THE Frontend SHALL proxy `/api` to `http://localhost:8080` through a proxy configuration file.
8. THE Frontend SHALL contain no field code, status label, step code, role code or entity code of any specific app, except the admin role code `PLATFORM_ADMIN` held in one configuration constant, and SHALL take app codes only from the route and from the Runtime_Config.

### Requirement 2: Session handling

**User Story:** As a user, I want my login to persist for the browser tab only and be applied to every request, so that I stay signed in safely while working.

#### Acceptance Criteria

1. WHEN the Backend accepts a login, THE Frontend SHALL store the Session in sessionStorage and expose it as a signal.
2. WHEN a login succeeds, THE Frontend SHALL request `GET /api/auth/me` and set the Session user id, display name and roles from the response.
3. WHEN the Frontend starts and sessionStorage holds a Session whose expiry time has not passed, THE Frontend SHALL restore that Session and request `GET /api/auth/me` to refresh the user id, display name and roles.
4. WHEN the Frontend starts and the stored Session has expired or is malformed, THE Frontend SHALL discard it.
5. WHILE a Session exists, THE Frontend SHALL add the header `Authorization: Bearer <token>` to every request sent to the Backend base path.
6. WHEN the Backend answers any request other than the login request with status 401, THE Frontend SHALL clear the Session and navigate to the login page, preserving the requested URL as a return target.
7. WHEN the user chooses Sign out, THE Frontend SHALL clear the Session and navigate to the login page.
8. THE Frontend SHALL store the Session in sessionStorage only and use no localStorage and no cookie for the token.
9. THE Frontend SHALL treat the Session, as refreshed by `GET /api/auth/me`, as the single source of the current user's id, display name and roles.

### Requirement 3: Login page

**User Story:** As a user, I want to sign in with my user name and password, so that I can reach my app.

#### Acceptance Criteria

1. THE Login_Page SHALL show a user name field, a password field and a Sign in button.
2. WHEN the user submits the login form with a blank user name or blank password, THE Login_Page SHALL show a message on the blank field and send no request.
3. WHEN the Backend answers a login with status 401, THE Login_Page SHALL show one message that does not say whether the user name or the password was wrong.
4. WHEN the Backend answers a login with status 400 and violations, THE Login_Page SHALL show each violation message on the control named by its `field`.
5. WHEN a login succeeds, THE Login_Page SHALL navigate to the stored return target or, when none exists, to the app home of the Default_App.
6. WHILE a login request is pending, THE Login_Page SHALL disable the Sign in button.

### Requirement 4: Public form page

**User Story:** As a citizen without an account, I want to fill in and submit a public form from my phone, so that I can file a request and get a reference number.

#### Acceptance Criteria

1. THE Frontend SHALL serve the public form page at a route of the shape `/public/:app/:form` that requires no Session.
2. WHEN the public form page opens, THE Frontend SHALL request `GET /api/public/apps/{app}` without an Authorization header and render the form whose code equals the `:form` route value.
3. IF the Public_Subset contains no form with the requested code, THEN THE Frontend SHALL show a "form not available" message and no input controls.
4. IF the Backend answers the Public_Subset request with status 404 or 409, THEN THE Frontend SHALL show a "form not available" message.
5. THE Public_Form_Page SHALL render the form sections, section titles, column counts, field labels, help texts, options and constraints through the Dynamic_Form using only the Public_Subset content.
6. WHEN the user submits a valid form, THE Public_Form_Page SHALL send `POST /api/apps/{app}/forms/{form}/submit` with a JSON object containing only the form's listed fields that hold a value.
7. WHEN the Backend answers the submit with status 201, THE Public_Form_Page SHALL replace the form with a confirmation showing `referenceNo` from the response.
8. WHEN the Backend answers the submit with status 422 or 400 and violations, THE Public_Form_Page SHALL show each violation on its control as described in Requirement 12 and keep the entered values.
9. IF the submit fails with a 5xx status or no response, THEN THE Public_Form_Page SHALL show a generic error message including the `requestId` when present, and keep the entered values.
10. WHILE a submit request is pending, THE Public_Form_Page SHALL disable the submit button.
11. WHILE the viewport is narrower than 576 CSS pixels, THE Public_Form_Page SHALL display every form section in a single column, with no horizontal scrolling and with interactive controls at least 44 CSS pixels high.
12. WHEN the Public_Form_Page is open, THE Frontend SHALL apply the branding (name, theme, and the primary colour and logo when the branding defines them) from the Public_Subset `app` object.

### Requirement 5: App shell and navigation

**User Story:** As a signed-in user, I want a branded shell with navigation to the views I may see, so that I can move around my app.

#### Acceptance Criteria

1. WHEN a user opens an authenticated route for an app, THE Shell SHALL load the Restricted_Definition with `GET /api/apps/{app}/definition`.
2. THE Shell SHALL build the navigation menu from the `views` of the Restricted_Definition, one menu item per view, labelled with the view `label` or, when absent, the view `code`, in declaration order.
3. WHERE a form in the Restricted_Definition can be submitted by the user, THE Shell SHALL add a "New" menu entry for it, labelled with the form `label` or `code`.
4. THE Shell SHALL show the app `name` in the header and, WHERE `app.branding.logo` is defined, the logo image.
5. WHERE `app.branding.primaryColor` is defined, THE Shell SHALL apply it as the primary colour of the NG-ZORRO theme.
6. THE Shell SHALL show the signed-in user's display name and a Sign out action in the header.
7. IF the Restricted_Definition request fails with status 404 or 409, THEN THE Shell SHALL show an "app not available" message with no menu.
8. WHILE the Restricted_Definition request is pending, THE Shell SHALL show a loading indicator.
9. WHERE the user is a Platform_Admin, THE Shell SHALL add an "Admin" menu entry linking to the Admin_Area.
10. WHEN the Restricted_Definition contains at least one view, THE Shell SHALL navigate the app home route `/app/:app` to the first view.
11. WHEN the Restricted_Definition contains no views, THE Shell SHALL show an empty-state message at the app home route.

### Requirement 6: Theme and accessibility

**User Story:** As a user, I want a professional dark interface with a subtle Halloween accent that stays readable, so that the app looks on-brand and is usable.

#### Acceptance Criteria

1. THE Frontend SHALL use a dark theme by default through the NG-ZORRO dark algorithm.
2. WHERE `app.branding.theme` is `light`, THE Frontend SHALL apply the light theme, and WHERE it is `auto`, THE Frontend SHALL follow the browser `prefers-color-scheme` setting.
3. THE Frontend SHALL show the Skeleton Key name and a skeleton-key mark in the login page and the Shell header, with an orange accent colour used for highlights only.
4. THE Frontend SHALL give body text, labels and button text a contrast ratio of at least 4.5:1 against their background, and large text and interactive component borders a ratio of at least 3:1, in the dark theme and in the light theme.
5. WHEN an app primary colour from the definition has a contrast ratio below 4.5:1 against the text colour placed on it, THE Frontend SHALL choose black or white text for that surface, whichever gives the higher ratio.
6. THE Frontend SHALL make every interactive element reachable and operable by keyboard with a visible focus indicator.
7. THE Frontend SHALL provide a text label or `aria-label` for every form control, icon-only button and status colour chip, and SHALL NOT rely on colour alone to convey a status or SLA_Warning.
8. THE Frontend SHALL provide an automated test that computes the contrast ratio of every foreground and background colour pair defined in the theme tokens and fails when a pair is below its required ratio.

### Requirement 7: Dynamic form renderer

**User Story:** As an app builder, I want one form renderer that builds itself from field definitions, so that new apps need no frontend code.

#### Acceptance Criteria

1. THE Dynamic_Form SHALL accept a list of field definitions, an optional layout of sections, an optional map of Field_Rules, initial values and a disabled flag, and SHALL expose the current values and validity as signals.
2. THE Dynamic_Form SHALL render a control for each of the field types TEXT, TEXTAREA, NUMBER, DECIMAL, DATE, DATETIME, BOOLEAN, SELECT, MULTISELECT, FILE, USER, REFERENCE, EMAIL, PHONE and LOCATION.
3. THE Dynamic_Form SHALL render TEXT as a text input, TEXTAREA as a multi-line input, NUMBER and DECIMAL as numeric inputs, DATE as a date picker, DATETIME as a date-and-time picker, BOOLEAN as a switch, SELECT as a single-choice select, MULTISELECT as a multiple-choice select, EMAIL as an email input, PHONE as a telephone input, and LOCATION as a latitude input and a longitude input.
4. THE Dynamic_Form SHALL render a FILE field as a disabled control with explanatory text stating that file upload is not yet available, and SHALL exclude FILE fields from the submitted values.
5. THE Dynamic_Form SHALL render SELECT and MULTISELECT options from the field `options`, showing the option `label` and submitting the option `value`.
6. WHERE a field has a `default` and no initial value is supplied, THE Dynamic_Form SHALL use the `default` as the initial value.
7. THE Dynamic_Form SHALL show the field `label`, and the `help` text when present, for each rendered control.
8. WHEN the effective Field_Rule of a field is `hidden`, THE Dynamic_Form SHALL render no control for that field and include no value for it in the submitted values.
9. WHEN the effective Field_Rule of a field is `readonly`, THE Dynamic_Form SHALL render the control disabled and exclude the field from the submitted values.
10. WHEN the effective Field_Rule of a field is `required`, THE Dynamic_Form SHALL attach a required validator to the control and mark the control as required.
11. WHEN the effective Field_Rule of a field is `optional`, THE Dynamic_Form SHALL render an enabled control with no required validator.
12. WHEN no Field_Rule map is supplied, THE Dynamic_Form SHALL use `required` for fields with `required: true` and `optional` for all other fields.
13. WHERE a field defines `maxLength`, `min`, `max` or `pattern`, THE Dynamic_Form SHALL attach a maximum-length, minimum, maximum or pattern validator respectively with the field's value, and attach an email-format validator to EMAIL fields.
14. WHERE a field defines `maxFiles` or `accept`, THE Dynamic_Form SHALL ignore both for rendering because FILE is disabled.
15. THE Dynamic_Form SHALL lay out each section with its `title` and with its `columns` value (1, 2 or 3) and, when no sections are supplied, SHALL render all non-hidden fields in one single-column section in field order.
16. WHEN the user submits the Dynamic_Form while it is invalid, THE Dynamic_Form SHALL mark every invalid control as touched, show its error message and emit no submit event.
17. THE Dynamic_Form SHALL convert user input to the JSON types the Backend expects: numbers for NUMBER and DECIMAL, `yyyy-MM-dd` strings for DATE, ISO 8601 strings with a UTC offset for DATETIME, booleans for BOOLEAN, arrays of strings for MULTISELECT and an object `{ lat, lng }` for LOCATION.
18. WHERE a field defines a `widget` key that a registered widget provides, THE Dynamic_Form SHALL render that widget in place of the built-in control (Requirement 8).

### Requirement 8: Custom widget registry

**User Story:** As an app author, I want to register a custom widget without editing the skeleton, so that an app can extend the UI.

#### Acceptance Criteria

1. THE Frontend SHALL export a `FIELD_WIDGETS` injection token that accepts multiple providers, each providing a registration with a unique widget key and a standalone component type.
2. THE Frontend SHALL define a widget contract through which a widget receives the field definition, the form control, the effective Field_Rule and the disabled state.
3. WHERE a field definition has a `widget` string equal to the key of a registered widget, THE Dynamic_Form SHALL render that widget in place of the built-in control for that field.
4. IF two registrations use the same widget key, THEN THE Frontend SHALL log an error naming the key at startup and use the first registration.
5. IF a field names a widget key for which no registration exists, THEN THE Dynamic_Form SHALL render the built-in control for the field type.
6. THE Frontend SHALL select a widget only from the optional `widget` string property of the field definition.

### Requirement 9: View renderers (TABLE)

**User Story:** As a user, I want a table of records with paging, sorting and filters, so that I can find and open records.

#### Acceptance Criteria

1. WHEN a TABLE view opens, THE Dynamic_Table SHALL request `GET /api/apps/{app}/entities/{entity}/records` with `view` set to the view code, `page` 1 and `size` 20 (or the user-chosen page size from 10, 20, 50 and 100).
2. THE Dynamic_Table SHALL render one column per entry of the view `columns`, in order, labelled with the entity field `label` for field codes and with a fixed label for `$` system columns.
3. WHEN a view declares no `columns`, THE Dynamic_Table SHALL render the entity `titleField` when present, plus the `$reference` column.
4. THE Dynamic_Table SHALL format cell values by field type: option labels for SELECT and MULTISELECT, localised dates for DATE and DATETIME, Yes or No for BOOLEAN, and plain text otherwise.
5. WHEN the user changes the page or page size, THE Dynamic_Table SHALL request the new page from the Backend and show the `total` from the response.
6. WHEN the user chooses a sortable column header, THE Dynamic_Table SHALL request the records with `sort` set to `<column>,asc` or `<column>,desc` and page 1.
7. WHEN the view declares `defaultSort` and the user has not sorted, THE Dynamic_Table SHALL request that sort.
8. THE Dynamic_Table SHALL render one filter control for each entry of the view `filters`, of a type matching the field type, and send each non-empty value as an equality query parameter named after the field code.
9. WHEN the user changes a filter, THE Dynamic_Table SHALL request page 1 with the new filter set.
10. WHEN the Backend answers the list request with status 400 and violations, THE Dynamic_Table SHALL show the violation messages above the table and keep the previous rows.
11. WHEN a list response contains no items, THE Dynamic_Table SHALL show an empty-state message.
12. WHEN the user chooses a row, THE Dynamic_Table SHALL navigate to the Record_Detail of that record.
13. THE Dynamic_Table SHALL render the `$status` column from the Record_Summary `status` as a chip with a text label and the `statusColor`, the `$step` column from `stepLabel`, the `$assignee` column from `assignee` and the `$dueAt` column from `dueAt` with its SLA_Warning.
14. WHILE a list request is pending, THE Dynamic_Table SHALL show a loading indicator.

### Requirement 10: View renderers (KANBAN and MY_TASKS)

**User Story:** As a user, I want a board of cards by status and a personal task inbox, so that I can see work at a glance.

#### Acceptance Criteria

1. THE Kanban_Board SHALL create one column for each status in the `statuses` of the workflow whose `entity` equals the view entity, in declaration order, labelled with the status `label`.
2. WHERE a status declares a `color`, THE Kanban_Board SHALL show that colour on the column header together with the status label text.
3. WHEN the Kanban_Board has loaded its records, THE Kanban_Board SHALL place each record in the column whose label equals the Record_Summary `status`.
4. THE Kanban_Board SHALL show on each card the record `$reference`, the `titleField` value when present, the `assignee` when present, and the `status` with its `statusColor` as a chip with a text label.
5. WHEN the user chooses a card, THE Kanban_Board SHALL navigate to the Record_Detail of that record.
6. THE Kanban_Board SHALL provide no drag-to-move behaviour for cards.
7. THE Kanban_Board SHALL load the records page by page from the records list endpoint with the view code and show a "load more" control while `total` exceeds the cards loaded.
8. IF a record's status matches no column, THEN THE Kanban_Board SHALL show the card in an additional "Other" column.
9. THE My_Tasks SHALL list the records returned by the records list endpoint for the MY_TASKS view, showing reference, title, `stepLabel`, `status` and `dueAt` for each, sorted by `$dueAt` ascending unless the view declares a `defaultSort`.
10. WHEN the user chooses an inbox item, THE My_Tasks SHALL navigate to the Record_Detail of that record.
11. WHEN the My_Tasks has no items, THE My_Tasks SHALL show an empty-state message.
12. WHEN the current time is later than an item's `dueAt` and the item's `ended` is false, THE Frontend SHALL show an "Overdue" SLA_Warning on that item in table rows, cards and inbox items.
13. WHEN the current time is not later than an item's `dueAt`, the remaining time is at most the Warning_Window and the item's `ended` is false, THE Frontend SHALL show a "Due soon" SLA_Warning on that item.
14. THE Frontend SHALL show each SLA_Warning with an icon and a text label, not by colour alone.
15. THE Frontend SHALL compute the SLA_Warning state through one pure function taking `dueAt`, the current time and the Warning_Window.

### Requirement 11: Record detail and workflow actions

**User Story:** As an assignee, I want to see a record with the fields I can edit and the actions I can take, so that I can move the work forward.

#### Acceptance Criteria

1. WHEN the Record_Detail opens, THE Record_Detail SHALL request the record, and, using the `instanceId` of the record, the Instance_View and the history of that instance.
2. THE Record_Detail SHALL render the entity fields through the Dynamic_Form using the `fieldRules` of the Instance_View.
3. THE Record_Detail SHALL show the record `$reference`, the current `stepLabel`, the current `status` with its `statusColor` and text, the assignee, and the `dueAt` with its SLA_Warning when due.
4. THE Record_Detail SHALL render one button per action in the Instance_View `actions`, labelled with the action `label`, in the given order, styled by the action `style` (`primary`, `default` or `danger`).
5. WHEN the Instance_View lists no actions, THE Record_Detail SHALL show the fields as read-only and no action buttons.
6. WHEN the user chooses an action whose `remarks` is `optional` or `required`, or whose `attachment` is `optional` or `required`, THE Record_Detail SHALL open a dialog containing a remarks input for non-hidden remarks and an attachment reference input for non-hidden attachment.
7. WHILE a dialog shows a remarks input whose rule is `required`, THE Record_Detail SHALL disable the dialog confirm button until the remarks are not blank.
8. WHILE a dialog shows an attachment input whose rule is `required`, THE Record_Detail SHALL disable the dialog confirm button until the attachment reference is not blank.
9. WHEN the user chooses an action that has a `confirm` text, THE Record_Detail SHALL show a confirmation dialog containing that text before sending the request.
10. WHEN the user confirms an action, THE Record_Detail SHALL send `POST /api/instances/{id}/actions/{code}` with the changed editable field values in `fields`, plus `remarks` and `attachment` when entered.
11. WHEN the action request succeeds, THE Record_Detail SHALL replace its state with the returned Instance_View, reload the record and the history, and show a success message.
12. WHEN the action request fails with status 422 or 403 and violations, THE Record_Detail SHALL map the violations to the field controls and the dialog inputs as described in Requirement 12 and keep the dialog or form values.
13. WHEN the action request fails with status 409, THE Record_Detail SHALL show the Api_Error message, reload the Instance_View, and keep the entered field values.
14. IF an action request fails with a 5xx status, THEN THE Record_Detail SHALL show a generic error message including the `requestId`.
15. WHILE an action request is pending, THE Record_Detail SHALL disable all action buttons.
16. THE Record_Detail SHALL render the history as a timeline in the order returned by the Backend, showing for each entry the time, the kind, the action code, the from and to step, the from and to status, the actor id, and the remarks.
17. WHEN the Instance_View `ended` is true, THE Record_Detail SHALL label the record as closed and show no action buttons.
18. WHERE the record has a null `instanceId`, THE Record_Detail SHALL render the fields using the `required` flags of the entity and no action buttons or history.

### Requirement 12: Error mapping

**User Story:** As a user, I want server validation messages to appear on the right field, so that I know what to fix.

#### Acceptance Criteria

1. THE Error_Mapper SHALL be a pure function that takes an Api_Error and a set of control names and returns, for each control name, the list of messages from the Violations whose `field` equals that control name, and a list of the remaining Violations.
2. WHEN a Violation `field` equals the code of a control in the form, THE Error_Mapper SHALL assign that Violation to that control.
3. WHEN several Violations share the same `field`, THE Error_Mapper SHALL assign all of them to that control in their original order.
4. WHEN a Violation has no `message`, THE Error_Mapper SHALL generate a message from its `rule` through a lookup table of known rule codes and fall back to the rule code text.
5. WHEN a Violation `field` equals no control name, THE Error_Mapper SHALL place the Violation in the unmatched list and THE Frontend SHALL show the unmatched messages in a form-level alert.
6. THE Error_Mapper SHALL neither drop nor duplicate any Violation: the count of assigned Violations plus unmatched Violations SHALL equal the count of input Violations.
7. WHEN an error is mapped to a control, THE Dynamic_Form SHALL mark the control invalid, set its error state, show the messages beneath the control and set the control's `aria-invalid` and `aria-describedby` attributes.
8. WHEN the user edits a control that carries a server error, THE Dynamic_Form SHALL clear that server error from the control.
9. THE Frontend SHALL show the `message` of an Api_Error that has no violations in a dismissible alert, and for a 5xx status SHALL show a generic text with the `requestId` and no server-provided detail.
10. WHEN a definition request fails with status 400 and `line` and `column` values, THE JSON_Editor SHALL show the line and column and move the caret to that position.

### Requirement 13: Admin area access and definitions list

**User Story:** As a Platform_Admin, I want to see the apps, their definition versions and open them, so that I can manage them.

#### Acceptance Criteria

1. THE Frontend SHALL serve the Admin_Area under `/admin` and guard it with a route guard that requires the `PLATFORM_ADMIN` role in the Session.
2. IF a user without the admin role opens an `/admin` route, THEN THE Frontend SHALL show a "not authorised" page.
3. WHEN the Admin_Area opens, THE Admin_Area SHALL request `GET /api/definitions/apps` and list the returned app codes.
4. WHEN the Platform_Admin chooses an app code, THE Admin_Area SHALL request `GET /api/apps/{app}/definitions` and show each version with its version number, status (DRAFT or PUBLISHED), content hash and id.
5. WHEN the Platform_Admin opens a version, THE Admin_Area SHALL load it with `GET /api/definitions/{id}` and show the JSON in the JSON_Editor.
6. WHEN the Platform_Admin chooses New definition, THE Admin_Area SHALL open the JSON_Editor with an empty minimal template that satisfies the schema's required top-level properties.
7. IF the Backend answers an Admin_Area request with status 403, THEN THE Admin_Area SHALL show the "not authorised" message.

### Requirement 14: Definition JSON editor, validator report and Publish

**User Story:** As a Platform_Admin, I want to edit definition JSON, see validator errors and publish, so that I can ship a new app version.

#### Acceptance Criteria

1. THE JSON_Editor SHALL show the definition as formatted JSON with two-space indentation in an editable monospaced text area.
2. WHEN the text is not parseable JSON, THE JSON_Editor SHALL show the parse error and disable Save.
3. WHEN the text is parseable JSON and has not changed for 500 milliseconds, THE JSON_Editor SHALL send `POST /api/definitions/validate` with the text and show the returned Validator_Report.
4. WHEN the Platform_Admin chooses Save on a new definition, THE JSON_Editor SHALL send `POST /api/definitions` with the text and show the returned version reference.
5. WHEN the Platform_Admin chooses Save on a DRAFT version, THE JSON_Editor SHALL send `PUT /api/definitions/{id}` and show the returned version reference.
6. WHEN the Platform_Admin chooses Save on a PUBLISHED version, THE JSON_Editor SHALL send `POST /api/apps/{app}/definitions/{base}/edit` and show the new DRAFT version reference.
7. WHEN the Backend returns a Validator_Report from validate, or rejects a save or a publish with status 422, THE JSON_Editor SHALL show the Validator_Report as a list with the `field` pointer, the `rule` and the `message` of each Violation, sorted as returned.
8. WHEN the Platform_Admin chooses a Violation whose `field` is a JSON Pointer, THE JSON_Editor SHALL move the caret to the start of the pointed location when it can be found in the text.
9. WHEN the Platform_Admin chooses Publish on a DRAFT version, THE JSON_Editor SHALL send `POST /api/definitions/{id}/publish`.
10. WHEN a publish succeeds, THE JSON_Editor SHALL show the version as PUBLISHED and a success message.
11. WHEN a publish fails with status 409, THE JSON_Editor SHALL show the Api_Error message.
12. THE JSON_Editor SHALL disable Publish for versions that are not DRAFT and for unsaved changes.
13. WHEN the Platform_Admin has unsaved changes and navigates away, THE JSON_Editor SHALL ask for confirmation.

### Requirement 15: Minimal form builder

**User Story:** As a Platform_Admin, I want to build a form by adding and ordering fields with a preview, so that I can create forms without writing JSON.

#### Acceptance Criteria

1. THE Form_Builder SHALL operate on the definition currently loaded in the JSON_Editor and select one entity and one form (or New form) of that definition.
2. THE Form_Builder SHALL offer an Add field action that appends a field with a generated unique `fieldCode` code, a default label and a chosen type from the 15 schema field types to the selected entity.
3. WHEN the chosen type is SELECT or MULTISELECT, THE Form_Builder SHALL require at least one option with a `value` and a `label` before the field is accepted.
4. WHEN the chosen type is REFERENCE, THE Form_Builder SHALL require the target entity code chosen from the entities of the definition.
5. THE Form_Builder SHALL let the Platform_Admin edit for a field its label, its `required` flag, `help`, and the constraints `min`, `max`, `maxLength` and `pattern` where valid for the type.
6. THE Form_Builder SHALL let the Platform_Admin reorder fields in the form section by drag and drop, and by move-up and move-down buttons operable by keyboard.
7. THE Form_Builder SHALL let the Platform_Admin remove a field from the form section and, separately, delete a field from the entity after confirmation.
8. WHEN the Platform_Admin changes a field, a section or the order, THE Form_Builder SHALL update a live preview rendered by the Dynamic_Form from the same data within one change-detection cycle.
9. THE Form_Builder SHALL write its result into the JSON_Editor text as App_Definition JSON that uses only properties defined in `schema/app-definition.schema.json`.
10. WHEN the Platform_Admin applies the builder result, THE Form_Builder SHALL keep all definition content outside the edited entity fields and form sections unchanged.
11. FOR ALL definitions edited by the Form_Builder, serialising the definition to JSON, parsing it, and rebuilding the builder model SHALL produce a model equal to the original (round-trip property).
12. IF a field code entered by the Platform_Admin does not match the field code pattern `^[a-z][a-zA-Z0-9_]{0,63}$` or duplicates another field code of the entity, THEN THE Form_Builder SHALL show an error on the code input and refuse the change.

### Requirement 16: Production build and deployment

**User Story:** As an operator, I want `docker compose up` to serve the frontend and let me change the app mapping without rebuilding, so that the whole stack starts in one command and one image serves several apps.

#### Acceptance Criteria

1. THE Frontend SHALL provide a multi-stage Dockerfile that builds the production bundle with Node and copies it into an Nginx image.
2. THE Nginx_Container SHALL serve the static bundle and answer unknown non-file paths with `index.html` so that client-side routes work on reload.
3. THE Nginx_Container SHALL proxy requests under `/api/` to the `app` service of `docker-compose.yml` on port 8080.
4. THE docker-compose.yml SHALL define a `frontend` service built from `skeleton/frontend`, with `restart: unless-stopped`, a healthcheck that requests the Nginx root, and a dependency on the `app` service being healthy.
5. THE docker-compose.yml SHALL publish the `frontend` service on host port 80 or a port set by an environment variable, and SHALL NOT require a second reverse proxy.
6. THE Nginx_Container SHALL send long-lived cache headers for hashed static assets and `no-cache` for `index.html` and for `assets/config.json`.
7. THE Nginx_Container SHALL send the response headers `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`.
8. WHEN the production build runs, THE Frontend build SHALL complete without errors and without TypeScript strict-mode violations.
9. THE docker-compose.yml SHALL mount a host copy of the Runtime_Config read-only onto `assets/config.json` of the Nginx_Container, so that replacing the host file and restarting the container changes the Host_Mapping without rebuilding the image.

### Requirement 17: Backend dependencies

**User Story:** As the owner of the Backend, I want a precise list of what the Frontend needs from the Backend, so that the separate backend spec delivers it before the Frontend is built.

#### Acceptance Criteria

1. THE Frontend SHALL rely on these Backend_Dependencies and implement no fallback, placeholder or workaround for any of them:
   - **BD-1 Current user endpoint:** `GET /api/auth/me` returning `{ userId, displayName, roles[] }` (Requirement 2).
   - **BD-2 Record workflow summary:** every record list item and every record view includes `instanceId`, `step`, `stepLabel`, `status`, `statusColor`, `assignee`, `dueAt` and `ended` (Requirements 9, 10 and 11).
   - **BD-3 Definition listing:** `GET /api/definitions/apps` returning the app codes that have definitions, and `GET /api/apps/{app}/definitions` returning the version references (`id`, `version`, `status`, `contentHash`, timestamps) of an app (Requirement 13).
   - **BD-4 Branding and presentation subset:** the `app` object of both the Public_Subset and the Restricted_Definition includes the app `branding` object, and the Restricted_Definition workflow presentation subset includes `statuses[].color`, `steps[].label` and `steps[].type` (Requirements 4, 5 and 10).
   - **BD-5 Schema widget property:** an optional `widget` string on the schema `field` (Requirement 8).
   - **BD-6 Validate without storing:** `POST /api/definitions/validate` returning the Validator_Report for a body without storing a version (Requirement 14).
   - **BD-7 File upload (not yet available):** a file upload and download endpoint; FILE fields stay disabled in the Frontend (Requirement 7.4).
2. THE Frontend SHALL keep every call to a Backend_Dependency endpoint in one API service class.

### Requirement 18: Property-based testing of the form renderer

**User Story:** As a maintainer, I want property-based tests of the form renderer, so that rule handling is verified across many generated field sets.

#### Acceptance Criteria

1. THE Frontend test suite SHALL use fast-check, pinned to an exact version, and run all tests with a single non-watch command that works in Windows CMD.
2. THE Frontend test suite SHALL include a property test that, for any generated list of field definitions covering all 15 field types and any generated Field_Rule map, the Dynamic_Form renders exactly one control for each field whose effective rule is not `hidden`, and no control for any field whose rule is `hidden`.
3. THE property test SHALL verify that a control is disabled if and only if its effective rule is `readonly` or its field type is FILE.
4. THE property test SHALL verify that a control carries a required validator if and only if its effective rule is `required` and its type is not FILE.
5. THE property test SHALL verify that a control carries a maximum-length, minimum, maximum or pattern validator if and only if the field definition declares `maxLength`, `min`, `max` or `pattern` respectively.
6. THE property test SHALL verify that the submitted values contain exactly the keys of fields whose effective rule is `optional` or `required` and whose type is not FILE and whose value is non-empty.
7. THE property test SHALL run at least 100 generated cases per property and print the seed and shrunk counterexample on failure.
8. THE Frontend test suite SHALL include a unit test that renders every one of the 15 field types and checks that FILE is disabled with the explanatory text.

### Requirement 19: Property-based testing of error mapping

**User Story:** As a maintainer, I want property-based tests of error mapping, so that every server violation reaches the right control.

#### Acceptance Criteria

1. THE Frontend test suite SHALL include a fast-check property test over the Error_Mapper with generated control name sets and generated Violation lists containing both matching and non-matching `field` values.
2. THE property test SHALL verify that every Violation whose `field` equals a control name appears in that control's list and in no other list.
3. THE property test SHALL verify that every Violation whose `field` equals no control name appears in the unmatched list.
4. THE property test SHALL verify that the total count of assigned and unmatched Violations equals the count of input Violations (no loss, no duplication).
5. THE property test SHALL verify that Violations with the same `field` keep their original relative order.
6. THE property test SHALL verify that every produced message is a non-empty string, using the Violation `message` when present and the rule lookup otherwise.
7. THE property test SHALL verify, using the Dynamic_Form with generated field sets, that after the mapped errors are applied each matched control is invalid and shows its messages, and each unmatched Violation is shown in the form-level alert.
8. THE property test SHALL run at least 100 generated cases and print the seed and shrunk counterexample on failure.
9. FOR ALL Api_Error values, mapping the same input twice SHALL return equal results (determinism).

### Requirement 20: Remaining non-functional behaviour

**User Story:** As a user, I want predictable loading and failure behaviour, so that the app is trustworthy.

#### Acceptance Criteria

1. WHEN any Backend request fails with no HTTP response, THE Frontend SHALL show a "cannot reach server" message and offer a retry action.
2. WHEN the Backend answers a request with status 403, THE Frontend SHALL show the Api_Error message and keep the user on the current page.
3. WHEN the Backend answers a request with status 404 for a record or instance, THE Frontend SHALL show a "not found" page with a link to the app home.
4. THE Frontend SHALL send no request containing the password, the token or record data to any host other than the origin that served the Frontend.
5. THE Frontend SHALL escape all definition-provided text (labels, help, option labels, remarks, history) when rendering and use no `innerHTML` binding with such text.
6. THE Frontend SHALL restrict logo images to `http`, `https` and same-origin relative paths and ignore other URL schemes.

### Requirement 21: Runtime configuration and host-to-app mapping

**User Story:** As an operator, I want a config file that maps host names to apps and can be replaced without rebuilding, so that one image serves several apps on different subdomains.

#### Acceptance Criteria

1. WHEN the Frontend starts, THE Frontend SHALL fetch `assets/config.json` and complete loading it before the router performs its first navigation.
2. THE Runtime_Config SHALL hold an optional `apiBasePath` string and a `hosts` object that maps lower-case host names, without port, to app codes.
3. IF the Runtime_Config request fails or its content is not a JSON object with a `hosts` object, THEN THE Frontend SHALL show a "configuration error" message and perform no routing.
4. THE Frontend SHALL resolve the Default_App by matching the lower-case host name of the current page exactly against the keys of the Host_Mapping, through one pure function that takes the Host_Mapping and a host name.
5. WHEN the user opens the root route `/`, THE Frontend SHALL redirect to `/app/<Default_App>`.
6. IF the host name of the current page matches no key of the Host_Mapping, THEN THE Frontend SHALL show a message that no app is configured for the host, together with an input for an app code that navigates to `/app/<entered code>`.
7. THE Frontend SHALL keep the route shape `/app/:app/...` for every authenticated app route, and the explicit `:app` route value SHALL take precedence over the Default_App.
8. THE Frontend SHALL read the Runtime_Config without a build-time import, so that replacing the file in the served directory changes the Host_Mapping and the Backend base path without rebuilding.
9. THE Frontend test suite SHALL include a plain unit test of the host-to-app resolution function covering an exact match, a match with upper-case input, a host that matches no key, and an empty Host_Mapping.
