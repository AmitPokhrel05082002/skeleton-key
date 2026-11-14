# Implementation Plan: Skeleton Frontend

## Overview

Build the Angular app in `skeleton/frontend/` in the order: project setup with pinned versions, runtime config and host resolution, API/session/interceptor, pure helpers, theme, dynamic form and widgets, error mapping, public form, login, shell, view renderers, record detail, admin (JSON editor and form builder), then Dockerfile, nginx and compose. Implementation language is TypeScript (the design is TypeScript).

Constraints that apply to every task:
- Do not change anything under `skeleton/backend`. Every Backend_Dependency is used as is, with no fallback (Requirement 17.1). The only fallback is showing the user id when a display name is missing (BD-8).
- No app-specific codes in the frontend except `PLATFORM_ADMIN` (Requirement 1.8).
- Write all files as UTF-8 without BOM and LF line endings, using the editor file tools. Never use `Set-Content` or `Out-File` for source files (Requirement 1.5).
- Commands are given in Windows CMD syntax and must not start watchers. Run all commands from the repository root unless a `cd` is shown.
- Property tests use fast-check (exact pin), at least 100 runs, print seed and counterexample, and carry a comment `Feature: skeleton-frontend, Property N: <title>`.
- Property tests for Properties 1, 2, 3, 5, 6, 7, 9 and 19 are required. All other property tests are optional. The contrast test (6.8) and the host-resolution unit test (21.9) are plain required unit tests, as the design states.

## Tasks

- [x] 1. Project setup and pinned versions
  - [x] 1.1 Scaffold the Angular project and pin every dependency
    - Run `npx -y @angular/cli@latest new skeleton-frontend --directory skeleton\frontend --standalone --routing --style=scss --skip-git --ssr=false` (CMD, one line), then read the generated `package.json` and `angular.json` to learn the unit test runner and its packages.
    - Resolve exact versions with `npm view <package> versions` for `@angular/*` 22.2.x, `@angular/cdk` 22.2.x, `ng-zorro-antd` 22.2.0 and `fast-check` 4.10.x; write them, and the generated runner packages, to `package.json` as exact versions (no `^` or `~`); run `npm install` to create `package-lock.json`.
    - Install NG-ZORRO with its prerequisites (animations, i18n locale, icons), enable strict TypeScript, and add ESLint with `ng lint` plus a `no-restricted-syntax` rule forbidding `innerHTML` bindings.
    - Set npm scripts `start`, `build`, `test` (single run, `ng test --watch=false`, adding `--browsers=ChromeHeadless` only if Karma is the runner) and `lint`; no shell-specific syntax.
    - Add `proxy.conf.json` mapping `/api` to `http://localhost:8080`, wire it into the `start` script, and add `.editorconfig` (`charset = utf-8`, `end_of_line = lf`).
    - Configure `angular.json` to copy `ng-zorro-antd.css` and `ng-zorro-antd.dark.css` from `node_modules/ng-zorro-antd` to `assets/themes/light.css` and `assets/themes/dark.css` as assets, not in the `styles` array.
    - Create the folder layout from the design (`core/`, `shared/`, `features/`) and `src/testing/`; verify with `cd skeleton\frontend` then `npm run build`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.7, 6.1, 18.1_

  - [ ]* 1.2 Write the source scan test
    - Scan `skeleton/frontend` sources: no `innerHTML` bindings, no `localStorage` or `document.cookie`, no app-specific codes other than `PLATFORM_ADMIN`, no `^` or `~` in `package.json`, no BOM and no CRLF.
    - _Requirements: 1.3, 1.5, 1.8, 2.8, 20.5_

- [ ] 2. Runtime configuration and host resolution
  - [ ] 2.1 Implement runtime config, host resolution and the app initializer
    - Create `core/config/host-resolver.ts` with pure `resolveDefaultApp(hosts, host)` (strip port, lower-case, own-property exact match) and `validateRuntimeConfig(value)`.
    - Create `RuntimeConfigService.load()` using `fetch('assets/config.json', { cache: 'no-store' })`, with `configError`, a config signal and an `apiBase` computed signal defaulting to `/api`.
    - Register it with `provideAppInitializer` and `withDisabledInitialNavigation()`, calling `router.initialNavigation()` after success only.
    - Add `public/assets/config.json` with a localhost mapping, and `ConfigErrorPage`, `NoAppPage` (host not mapped, with app-code input navigating to `/app/<code>`), and the root `/` redirect component in `app.routes.ts`. The explicit `:app` route value always wins.
    - _Requirements: 1.6, 21.1, 21.2, 21.3, 21.4, 21.5, 21.6, 21.7, 21.8_

  - [ ] 2.2 Write unit tests for host resolution
    - Cover exact match, upper-case input, host with port, no match, empty mapping.
    - _Requirements: 21.9_

  - [ ]* 2.3 Write property test for host resolution
    - **Property 13: Host resolution**
    - **Validates: Requirements 21.3, 21.4, 21.9**

  - [ ]* 2.4 Write unit tests for the config loading flow
    - Initializer completes before navigation; invalid config shows ConfigErrorPage and starts no routing; replacing the file changes the mapping.
    - _Requirements: 21.1, 21.3, 21.8_

- [ ] 3. Session, interceptor and API service
  - [ ] 3.1 Define API types, ApiError and display-name helper
    - Create `core/api/api.types.ts` with the data models of the design. Read the backend DTOs (read only) and align field names; `HistoryEntry` must match the backend exactly (`kind`, `actionCode`, `fromStep`, `toStep`, `fromStatus`, `toStatus`, `actorId`, `actorName`, `remarks`, `attachmentRef`, `detail`, `eventTime`, `timeInStepMs`); note any other mismatch as a comment in the file instead of working around it.
    - Create `core/api/api-error.ts` with `toApiError()` mapping no response to `{ status: 0, code: 'NETWORK' }` and tolerating non-JSON bodies (nginx 502 pages).
    - Create `core/pure/display-name.ts` with `displayName(name, id)` (name, else id, else `-`).
    - _Requirements: 9.13, 10.4, 11.3, 11.16, 17.1, 20.1_

  - [ ] 3.2 Implement ApiService
    - One class in `core/api/api.service.ts` with every endpoint method listed in the design (auth, public, definition, submit, records, instances, history, admin definitions).
    - `ListQuery` drops empty filter values. `validateDefinition` and `createDefinition` send the raw editor text as `application/json` so the backend reports `line` and `column`.
    - All backend calls live in this class only.
    - _Requirements: 4.2, 9.1, 11.1, 11.10, 13.3, 13.4, 14.3, 17.2_

  - [ ] 3.3 Implement SessionService
    - Create `core/auth/session.service.ts` with the `session` signal, `roles`, `isPlatformAdmin`, `login()` (POST login then GET `/auth/me`), `restore()` and `clear()`, plus the exported `ADMIN_ROLE = 'PLATFORM_ADMIN'` constant.
    - Create pure `parseStoredSession(text, now)` that never throws and rejects malformed or expired values.
    - Use `sessionStorage` only; call `restore()` from the app initializer after the config; overwrite id, name and roles from `/auth/me`.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.7, 2.8, 2.9_

  - [ ] 3.4 Implement auth interceptor and guards
    - `auth.interceptor.ts`: add the Bearer header only to URLs under `apiBase` and not under `{apiBase}/public/`; on 401 (not the login call) clear the Session and navigate to `/login?returnUrl=...`.
    - `auth.guard.ts` (redirects to login keeping the return target) and `admin.guard.ts` (requires `ADMIN_ROLE`, else navigates to NotAuthorised). Register the interceptor with `provideHttpClient(withInterceptors(...))`.
    - Add `NotFoundPage` and `NotAuthorisedPage`.
    - _Requirements: 2.5, 2.6, 4.2, 13.1, 13.2, 20.3, 20.4_

  - [ ]* 3.5 Write property test for session restore
    - **Property 16: Session restore is safe**
    - **Validates: Requirements 2.3, 2.4**

  - [ ]* 3.6 Write unit tests for interceptor and session
    - Bearer added under `apiBase`, not added to other origins or the public call, 401 clears the Session, expired session discarded on start.
    - _Requirements: 2.3, 2.4, 2.5, 2.6, 4.2, 20.4_

- [ ] 4. Pure helpers and shared test generators
  - [ ] 4.1 Implement the form model layer
    - Create `core/pure/field-rules.ts` (`effectiveRule`), `features/form/form-model.ts` (`FieldDef`, `SectionDef`, `planControls`, `collectValues`, `isEmptyValue`, `initialValues`, `layoutSections`) and `core/pure/value-codec.ts` (`toJsonValue`, `fromJsonValue`).
    - DATETIME is written with the local UTC offset (`Z` when zero); `accept` and `maxFiles` are ignored.
    - _Requirements: 7.4, 7.6, 7.8, 7.9, 7.10, 7.11, 7.12, 7.13, 7.14, 7.15, 7.17_

  - [ ] 4.2 Create shared fast-check generators
    - Create `src/testing/arbitraries.ts` with `arbFieldDef` (all 15 types, optional constraints, options for SELECT and MULTISELECT), `arbRuleMap`, `arbViolation`, `arbApiError`, `arbDefinition`, `arbHost`.
    - _Requirements: 18.1, 18.7, 19.1_

  - [ ] 4.3 Write property test for submitted values
    - **Property 3: Submitted values contain exactly the editable non-empty fields**
    - **Validates: Requirements 7.4, 7.8, 7.9, 18.6**

  - [ ]* 4.4 Write property test for type conversion
    - **Property 4: Type conversion round trip and JSON types**
    - **Validates: Requirements 7.17**

  - [ ] 4.5 Implement SLA, safe URL and error text helpers
    - `core/pure/sla.ts` with `WARNING_WINDOW_MS` and `slaState(dueAt, now, windowMs, ended)`.
    - `safeLogoUrl(url)` (http, https or same-origin relative path only) and `errorText(err)` (5xx: generic text plus `requestId`, never the server message).
    - _Requirements: 10.12, 10.13, 10.15, 12.9, 20.6_

  - [ ]* 4.6 Write property test for SLA state
    - **Property 8: SLA state classification**
    - **Validates: Requirements 10.12, 10.13, 10.15**

  - [ ]* 4.7 Write property test for safe display helpers
    - **Property 20: Safe display helpers**
    - **Validates: Requirements 9.13, 10.4, 11.3, 11.16, 12.9, 20.6**

- [ ] 5. Checkpoint - Ensure all tests pass
  - Run `cd skeleton\frontend` then `npm run test` and `npm run build`. Ensure all tests pass, ask the user if questions arise.

- [ ] 6. Theme and accessibility
  - [ ] 6.1 Implement contrast helpers and theme tokens
    - `core/pure/contrast.ts` (`contrastRatio` using WCAG luminance, `onColor`), `core/theme/theme-tokens.ts` (`THEME_TOKEN_PAIRS` for dark, light and the orange accent) and pure `resolveTheme(mode, prefersDark)`.
    - _Requirements: 6.3, 6.4, 6.5, 6.8_

  - [ ] 6.2 Implement ThemeService and the Primary_Override
    - `ThemeService` owns one `<link id="sk-theme">`; `apply(mode)` swaps its `href` between `assets/themes/dark.css` and `light.css` and sets `data-theme`; `auto` listens to `prefers-color-scheme` and removes the listener when another mode is applied. Dark is the default. No `NzConfigService` theming.
    - `applyBranding(primaryColor?)` sets or removes `--sk-primary` and `--sk-on-primary` (from `onColor`) on `<html>`.
    - `primary-override.scss` applies the variables to header, primary buttons, focus ring, Kanban headers and status chips only. Add the global `:focus-visible` outline and the Skeleton Key mark/name styles with the orange accent for highlights.
    - _Requirements: 5.5, 6.1, 6.2, 6.3, 6.5, 6.6_

  - [ ] 6.3 Write the theme contrast unit test
    - Iterate every pair in `THEME_TOKEN_PAIRS` and fail below its minimum; check `onColor` against a sample of primary colours.
    - _Requirements: 6.4, 6.8_

  - [ ]* 6.4 Write property test for contrast helpers
    - **Property 14: Contrast helpers**
    - **Validates: Requirements 6.4, 6.5, 6.8**

  - [ ]* 6.5 Write unit tests for ThemeService
    - `apply` swaps the single link `href`; `auto` reacts to a simulated setting change and stops after another mode; `applyBranding` sets and removes both variables.
    - _Requirements: 6.1, 6.2, 6.5_

- [ ] 7. Dynamic form and widget registry
  - [ ] 7.1 Implement the widget token and registry
    - `shared/widgets/field-widget.token.ts` and `field-widget.types.ts`: `FIELD_WIDGETS` multi token, widget contract (field, control, rule, disabled signal inputs), `WidgetRegistry` keeping the first registration per key and logging `console.error` naming each duplicate key.
    - _Requirements: 8.1, 8.2, 8.4, 8.6_

  - [ ] 7.2 Implement shared presentation components
    - `StatusChip` (always shows label text, text colour via `onColor`), `SlaBadge` (icon plus text, nothing for `NONE`), `ErrorAlert` (message, generic text with `requestId` for 5xx, Retry output for status 0), `EmptyState`. All with `OnPush` and `aria-label` where icon-only.
    - _Requirements: 6.5, 6.7, 10.14, 12.9, 20.1_

  - [ ] 7.3 Implement DynamicFormComponent
    - Inputs `fields`, `sections`, `rules`, `initial`, `disabled`; outputs `submitted`; signals `values` and `valid`.
    - Build the reactive `FormGroup` from `planControls`, rebuilt when inputs change while keeping values of unchanged codes. Hidden fields get no control.
    - Layout: sections with `columns` 1/2/3 grid, a single column under 576 px, and a default single section when `sections` is not given.
    - Submit: an invalid form marks all controls touched and emits nothing; a valid form emits the collected values through `submitted`.
    - Render each control through the `FieldControlComponent` selector (`<sk-field-control>`). Until task 7.4 lands, create a minimal placeholder stub of that selector (same selector and inputs, no behaviour) in the file task 7.4 will own, so 7.3 compiles; task 7.4 replaces the stub.
    - _Requirements: 7.1, 7.4, 7.6, 7.8, 7.9, 7.10, 7.11, 7.12, 7.15, 7.16, 7.18_

  - [ ] 7.4 Implement FieldControlComponent
    - Built-in NG-ZORRO controls for all 15 field types: LOCATION as two numeric inputs, USER and REFERENCE as text inputs, FILE as a disabled control with the hint "File upload is not yet available".
    - Labels bound with `for`/`id` or `aria-label`; `help` shown via `nz-form-extra`; limit validators and type conversion applied through the form model helpers.
    - Resolve the widget through `WidgetRegistry` from the field `widget` string, falling back to the built-in control when it is not registered.
    - Replace the placeholder stub from task 7.3 with this component, keeping the `<sk-field-control>` selector and inputs.
    - _Requirements: 6.7, 7.2, 7.3, 7.5, 7.7, 7.13, 7.17, 8.3, 8.5_

  - [ ] 7.5 Write property test for controls following effective rules
    - **Property 1: Controls follow effective rules**
    - **Validates: Requirements 7.8, 7.9, 7.10, 7.11, 7.12, 18.2, 18.3, 18.4**

  - [ ] 7.6 Write property test for limit validators
    - **Property 2: Limit validators match declared constraints**
    - **Validates: Requirements 7.13, 7.14, 18.5**

  - [ ] 7.7 Write the unit test rendering all 15 field types
    - FILE is disabled and shows the explanatory text; every type renders its control.
    - _Requirements: 7.2, 7.3, 7.4, 18.8_

  - [ ]* 7.8 Write property test for the widget registry
    - **Property 15: Widget registry keeps the first registration**
    - Add a component test where a widget registered through `FIELD_WIDGETS` renders instead of the built-in control.
    - **Validates: Requirements 8.3, 8.4, 8.5**

  - [ ]* 7.9 Write property test for initial values and section layout
    - **Property 22: Initial values and section layout**
    - **Validates: Requirements 7.6, 7.15**

- [ ] 8. Error mapping
  - [ ] 8.1 Implement the Error_Mapper
    - `core/pure/rule-messages.ts` (rule lookup table) and `core/pure/error-mapper.ts` with `mapErrors(err, controlNames)` and `violationMessage(v)` (message, else lookup, else rule text, else `Invalid value`; never empty). Single pass keeping order, no loss or duplication.
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

  - [ ] 8.2 Write property test for violation assignment
    - **Property 5: Error mapping assigns every violation exactly once**
    - **Validates: Requirements 12.1, 12.2, 12.5, 12.6, 19.2, 19.3, 19.4**

  - [ ] 8.3 Write property test for order, messages and determinism
    - **Property 6: Error mapping preserves order, yields non-empty messages and is deterministic**
    - **Validates: Requirements 12.3, 12.4, 19.5, 19.6, 19.9**

  - [ ] 8.4 Wire server errors into the Dynamic_Form
    - Add the `serverError` input and `unmatchedErrors` signal to `DynamicFormComponent`: run `mapErrors` over rendered control names, call `setErrors({ server })`, mark touched, show messages in an element referenced by `aria-describedby` with `aria-invalid="true"`, show unmatched messages in the form-level `ErrorAlert`, and clear a control's server error when the user edits it.
    - _Requirements: 12.5, 12.7, 12.8, 12.9_

  - [ ] 8.5 Write property test for applied server errors
    - **Property 7: Applied server errors reach the right controls**
    - **Validates: Requirements 12.7, 12.8, 19.7, 19.8**

- [ ] 9. Checkpoint - Ensure all tests pass
  - Run `cd skeleton\frontend` then `npm run test` and `npm run lint`. Ensure all tests pass, ask the user if questions arise.

- [ ] 10. Public form page
  - [ ] 10.1 Implement PublicFormPage
    - Route `/public/:app/:form` (no guard). Call `publicApp(app)` without a session, pick the form by code, build `FieldDef[]` from the Public_Subset, and render through `DynamicFormComponent`.
    - Missing form, 404 or 409 show "form not available" with no controls. Submit sends only listed fields that hold a value; 201 shows the confirmation with `referenceNo`; 422/400 uses `serverError` and keeps values; 5xx or no response shows a generic error with `requestId` and keeps values; the button is disabled while pending.
    - Apply branding through `ThemeService`; under 576 px use one column with controls at least 44 px high.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12_

  - [ ]* 10.2 Write unit tests for the public form page
    - Not-available cases, 201 confirmation, 422 keeps values, 5xx generic message with `requestId`, pending disables the button, no Authorization header.
    - _Requirements: 4.2, 4.3, 4.4, 4.7, 4.8, 4.9, 4.10_

- [ ] 11. Login page
  - [ ] 11.1 Implement LoginPage
    - Route `/login`. Reactive form with `username` and `password`; blank values show a field message and send nothing; 401 shows one neutral message; 400 violations map onto the controls through `mapErrors`; success navigates to the stored return target or `/app/<Default_App>`; the button is disabled while pending. Show the Skeleton Key name and mark with the orange accent.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 6.3_

  - [ ]* 11.2 Write unit tests for the login page
    - Blank fields send nothing, 401 neutral message, 400 violations on controls, return target navigation.
    - _Requirements: 3.2, 3.3, 3.4, 3.5_

- [ ] 12. App shell and navigation
  - [ ] 12.1 Implement DefinitionStore, Shell and app home
    - `core/definition/definition.store.ts` (signals, restricted definition per app) and pure `buildMenu(def, roles)` (views in order labelled by `label` or `code`, "New" entries for submittable forms, "Admin" only for `PLATFORM_ADMIN`).
    - `ShellComponent` at `/app/:app` guarded by `authGuard`: loading indicator, "app not available" with no menu on 404/409, header with safe logo, app name, display name and Sign out, and calls to `ThemeService.apply` and `applyBranding`. `AppHomeComponent` redirects to the first view or shows an empty state.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 5.11, 2.7, 20.6_

  - [ ]* 12.2 Write property test for menu construction
    - **Property 21: Menu construction**
    - **Validates: Requirements 5.2, 5.3, 5.9**

  - [ ]* 12.3 Write unit tests for the shell
    - Loading state, app-not-available, Sign out clears the Session, empty-state app home, Admin entry only for admins.
    - _Requirements: 5.7, 5.8, 5.9, 5.11, 2.7_

- [ ] 13. View renderers
  - [ ] 13.1 Implement pure view helpers
    - `formatCell(field, value)` (option labels, localised dates, Yes/No, plain text; never throws), `resolveColumns(view, entity)`, `buildListQuery(state)` (non-empty filters only, `defaultSort` only when the user has not sorted, page 1 on sort or filter change) and `groupByStatus(records, statuses)` (declaration order, "Other" column, input order kept).
    - _Requirements: 9.2, 9.3, 9.4, 9.6, 9.7, 9.8, 9.9, 10.1, 10.3, 10.8_

  - [ ] 13.2 Write property test for Kanban grouping
    - **Property 9: Kanban grouping places every record once**
    - **Validates: Requirements 10.1, 10.3, 10.8**

  - [ ] 13.3 Implement DynamicTableComponent
    - Single `switchMap` pipeline for page, size, sort and filter changes; defaults page 1, size 20 with sizes 10/20/50/100; filter controls by field type; `$status`, `$step`, `$assignee` (with `displayName`), `$dueAt` (with `SlaBadge`) columns; 400 violations shown above the table keeping previous rows; empty state; loading indicator; row click navigates to `/app/:app/records/:entity/:id`.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11, 9.12, 9.13, 9.14_

  - [ ] 13.4 Implement KanbanComponent
    - Columns from the first workflow statuses for the view entity; headers use the status `color` (else `--sk-primary`) with `onColor` text and the label always shown; cards show reference, title, assignee via `displayName`, status chip and `SlaBadge`; "Other" column only when non-empty; "Load more" while `total` exceeds loaded (page size 50); no drag behaviour; card click navigates to Record_Detail.
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.12, 10.13, 10.14_

  - [ ] 13.5 Implement MyTasksComponent and ViewHostPage
    - `MyTasksComponent`: records list for the MY_TASKS view, sorted by `$dueAt` ascending unless `defaultSort`, showing reference, title, `stepLabel`, status chip and due date with `SlaBadge`; empty state; click navigates to Record_Detail.
    - `ViewHostPage` at `/app/:app/views/:view` switches on `view.type`; register the route.
    - _Requirements: 10.9, 10.10, 10.11, 10.12, 10.13, 10.14_

  - [ ]* 13.6 Write property test for table columns and query building
    - **Property 10: Table columns and query building**
    - **Validates: Requirements 9.2, 9.3, 9.6, 9.7, 9.8, 9.9**

  - [ ]* 13.7 Write property test for cell formatting
    - **Property 11: Cell formatting is total and label-preserving**
    - **Validates: Requirements 9.4**

  - [ ]* 13.8 Write unit tests for the view renderers
    - Table: initial request parameters, page-size change, sort change, filter change resets page, 400 keeps rows, empty state, row navigation. Kanban: load more while `total` exceeds loaded, no drag handlers, `displayName` fallback to the id. My tasks: default sort.
    - _Requirements: 9.1, 9.5, 9.6, 9.9, 9.10, 9.11, 9.12, 10.6, 10.7, 10.9_

- [ ] 14. Record detail and workflow actions
  - [ ] 14.1 Implement pure action helpers
    - `actionDialogReady(action, remarks, attachment)` (each `required` input must be non-blank) and `buildActionBody(initial, current, rules, remarks, attachment)` (only changed editable fields; `remarks` and `attachment` only when non-blank).
    - _Requirements: 11.7, 11.8, 11.10_

  - [ ] 14.2 Implement RecordDetailComponent
    - Route `/app/:app/records/:entity/:id`. Load the record, then instance and history in parallel when `instanceId` is not null; null `instanceId` shows fields with entity `required` flags and no actions or history.
    - Header shows reference, `stepLabel`, status chip, assignee via `displayName`, `dueAt` with `SlaBadge`; fields via `DynamicFormComponent` with `instance.fieldRules`; action buttons in order mapped to `style`, all disabled while pending; ended instance shows a "Closed" tag and no buttons.
    - Request handling: success replaces state with the returned Instance_View, reloads record and history, shows a success message; 422/403 maps violations to the form and the dialog inputs and keeps them open; 409 shows the message, reloads the instance and keeps values; 5xx shows a generic message with `requestId`; 404 shows NotFound with the app-home link.
    - Invoke `ActionDialogComponent` (for `remarks` or `attachment` not hidden) and `NzModalService.confirm` (for `confirm` text without a dialog) through the interface defined in task 14.3, and render `HistoryTimelineComponent` from task 14.3.
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.9, 11.10, 11.11, 11.12, 11.13, 11.14, 11.15, 11.16, 11.17, 11.18, 20.2, 20.3_

  - [ ] 14.3 Implement ActionDialogComponent and HistoryTimelineComponent
    - `ActionDialogComponent`: gated by `actionDialogReady` (confirm disabled until each `required` input is non-blank), shows the action `confirm` text, and maps `remarks` and `attachment` violations onto its inputs. Expose inputs and outputs (action, pending, violations; confirmed, cancelled) that task 14.2 consumes.
    - `HistoryTimelineComponent`: `nz-timeline` in backend order showing `eventTime`, `kind`, `actionCode`, `fromStep`/`toStep`, `fromStatus`/`toStatus`, `remarks`, and `attachmentRef` and `detail` when present, with `timeInStepMs` formatted as a duration if shown; actor via `displayName(actorName, actorId)`; interpolation only (no `innerHTML`).
    - _Requirements: 11.7, 11.8, 11.10, 11.16, 20.5_

  - [ ] 14.4 Implement the authenticated form page
    - `AuthedFormPage` at `/app/:app/forms/:form/new` for the "New" menu entries, using `DynamicFormComponent` and `submitForm`, showing the `referenceNo` confirmation and server errors as in the public form.
    - _Requirements: 5.3, 7.1, 12.7_

  - [ ]* 14.5 Write property test for action dialog and body
    - **Property 12: Action dialog readiness and action body**
    - **Validates: Requirements 11.7, 11.8, 11.10**

  - [ ]* 14.6 Write unit tests for record detail
    - Button order and style, dialog gating, confirm text, success refresh, 409 refresh, null `instanceId`, ended instance, history order (entries rendered in the returned order, with `eventTime`, `actionCode`, `attachmentRef`, `detail` and `timeInStepMs` shown as specified).
    - _Requirements: 11.4, 11.6, 11.9, 11.11, 11.13, 11.16, 11.17, 11.18_

- [ ] 15. Checkpoint - Ensure all tests pass
  - Run `cd skeleton\frontend` then `npm run test` and `npm run build`. Ensure all tests pass, ask the user if questions arise.

- [ ] 16. Admin area
  - [ ] 16.1 Implement JSON editor helpers
    - Pure `formatJson`, `jsonState`, `pointerToOffset(text, pointer)`, `lineColumnToOffset(text, line, column)`, `chooseSave(state)` (POST for new, PUT for DRAFT, edit for PUBLISHED), `canPublish(state)` and `minimalDefinitionTemplate()`.
    - _Requirements: 12.10, 13.6, 14.1, 14.2, 14.4, 14.5, 14.6, 14.8, 14.12_

  - [ ] 16.2 Implement the admin shell and definitions list
    - `/admin` route with `authGuard` and `adminGuard`; list app codes, versions per app (version, status, content hash, id), open a version into the editor, New definition from the template; a 403 shows the "not authorised" message.
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7_

  - [ ] 16.3 Implement the JsonEditorComponent
    - Monospaced textarea bound to a signal; parse error disables Save; after 500 ms of no change `validateDefinition` runs through `debounceTime` and `switchMap`; Validator_Report list with `field`, `rule`, `message`; clicking a pointer violation moves the caret; 400 with `line`/`column` shows them and moves the caret.
    - Save by state (POST, PUT, edit), Publish only for a saved DRAFT with no unsaved changes, 422 shows the report, 409 on publish shows the message, `canDeactivate` guard confirms unsaved changes. Register the editor route.
    - _Requirements: 12.10, 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10, 14.11, 14.12, 14.13_

  - [ ]* 16.4 Write property test for JSON editor helpers
    - **Property 17: JSON editor helpers**
    - **Validates: Requirements 12.10, 14.1, 14.2, 14.4, 14.5, 14.6, 14.8, 14.12**

  - [ ] 16.5 Implement the form builder model
    - `features/admin/builder-model.ts`: `BuilderModel`, `extractModel`, `applyModel` (returns a new object, touches only the selected entity fields and form sections, writes only schema-defined properties and removes `undefined` ones), `generateFieldCode`, `validateFieldCode`, `validateField`, `moveField`.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.6, 15.9, 15.10, 15.12_

  - [ ] 16.6 Write property test for the builder round trip and isolation
    - **Property 19: Form builder model round trip and isolation**
    - **Validates: Requirements 15.9, 15.10, 15.11**

  - [ ]* 16.7 Write property test for builder field operations
    - **Property 18: Form builder field operations**
    - **Validates: Requirements 15.2, 15.3, 15.4, 15.6, 15.12**

  - [ ] 16.8 Implement the form builder UI
    - Panel beside the editor reading and writing the editor text: entity and form selector, Add field with type choice, options editor for SELECT/MULTISELECT, target entity for REFERENCE, edit of label, `required`, `help`, `min`, `max`, `maxLength`, `pattern`; reorder with CDK `DragDrop` plus keyboard-operable move-up/down buttons with `aria-label`; remove from form, and delete from entity with `nz-popconfirm`; field code errors shown on the input and refused.
    - Live preview with `<sk-dynamic-form>` driven by computed signals from the same model; apply writes via `applyModel` into the editor text.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 15.10, 15.12_

  - [ ]* 16.9 Write unit tests for the admin area
    - Guard behaviour, versions list, save endpoint per state, publish enabled states, unsaved-changes confirmation, builder apply keeps other content.
    - _Requirements: 13.1, 13.2, 13.4, 14.4, 14.5, 14.6, 14.12, 14.13, 15.10_

- [ ] 17. Dockerfile, nginx and compose
  - [ ] 17.1 Create the Dockerfile and nginx configuration
    - `skeleton/frontend/Dockerfile`: multi-stage, Node (pinned LTS) running `npm ci` and `npm run build -- --configuration production`, then pinned `nginx:<version>-alpine` with the output path confirmed against `angular.json`.
    - `nginx.conf` and `security-headers.conf`: `/api/` proxied to `http://app:8080` with `Host` and `X-Request-Id`; SPA fallback to `index.html`; `no-cache` for `index.html` and `assets/config.json`; immutable long cache for hashed assets; `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY` included in every location. Add `.dockerignore`.
    - _Requirements: 16.1, 16.2, 16.3, 16.6, 16.7, 16.8_

  - [ ] 17.2 Add the frontend service to docker-compose.yml
    - Read the existing `docker-compose.yml` and extend it in place with a `frontend` service built from `./skeleton/frontend`, `restart: unless-stopped`, published on `${FRONTEND_PORT:-80}:80`, `depends_on` the `app` service healthy, a `wget` healthcheck on the Nginx root, and the host `skeleton/frontend/public/assets/config.json` mounted read-only onto `/usr/share/nginx/html/assets/config.json`. Do not add a second reverse proxy.
    - Verify the file with `docker compose config` and the image with `docker build skeleton\frontend`.
    - _Requirements: 16.3, 16.4, 16.5, 16.9_

- [ ] 18. Final checkpoint - Ensure all tests pass
  - Run `cd skeleton\frontend` then `npm run lint`, `npm run test` and `npm run build`; from the repository root run `docker compose config`. Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP.
- Required property tests: Properties 1, 2, 3, 5, 6, 7, 9 and 19. All other property tests are optional.
- The theme contrast test (6.3) and host-resolution unit test (2.2) are required by Requirements 6.8 and 21.9 and are not optional.
- Each task references specific requirements for traceability.
- Backend files under `skeleton/backend` are read only and never changed by this plan.
- Full accessibility conformance needs manual testing with assistive technologies; automated tests cover only contrast and labels.
- Useful commands (CMD): `cd skeleton\frontend`, `npm ci`, `npm run test`, `npm run build`, `npm run lint`, `docker compose up -d --build`.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1", "3.1", "6.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "2.4", "3.2", "4.1", "4.5", "6.3", "6.4", "8.1", "16.1"] },
    { "id": 3, "tasks": ["3.3", "4.2", "4.6", "6.2", "7.1", "7.2", "13.1", "14.1", "16.5"] },
    { "id": 4, "tasks": ["3.4", "3.5", "4.3", "4.4", "4.7", "6.5", "7.3", "7.4", "8.2", "8.3", "13.2", "13.6", "13.7", "14.5", "16.4", "16.6", "16.7"] },
    { "id": 5, "tasks": ["3.6", "7.5", "7.6", "7.7", "7.8", "7.9", "8.4"] },
    { "id": 6, "tasks": ["8.5", "10.1", "14.3"] },
    { "id": 7, "tasks": ["10.2", "11.1"] },
    { "id": 8, "tasks": ["11.2", "12.1"] },
    { "id": 9, "tasks": ["12.2", "12.3", "13.3", "13.4"] },
    { "id": 10, "tasks": ["13.5"] },
    { "id": 11, "tasks": ["13.8", "14.2"] },
    { "id": 12, "tasks": ["14.4", "14.6"] },
    { "id": 13, "tasks": ["16.2"] },
    { "id": 14, "tasks": ["16.3"] },
    { "id": 15, "tasks": ["16.8"] },
    { "id": 16, "tasks": ["16.9", "17.1"] },
    { "id": 17, "tasks": ["17.2"] }
  ]
}
```
