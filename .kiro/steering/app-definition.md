---
inclusion: always
---

# App definition: semantics

The JSON Schema below defines the **shape** of an app definition:

#[[file:schema/app-definition.schema.json]]

A complete example lives in `examples/grievance.app.json`.

This document defines the **behaviour** the schema cannot express. The engine, validator and tests must follow it exactly.

## Versioning
- The **whole app definition document** is versioned, identified by `(app code, version)`. The version number is assigned by the backend on store and is not part of the JSON. Published versions are immutable: editing creates version + 1.
- A running instance is pinned to one document version plus its workflow code until it ends.

## Statuses
- Statuses are labels declared per workflow, each with a category (`OPEN`, `IN_PROGRESS`, `DONE`, `REJECTED`).
  There are no status enums in Java or TypeScript.
- On start the status is `initialStatus`, or the first `OPEN` status if that is not set. An action's `status` sets the
  label; reaching an `END` step sets its `outcome`.

## Routing (one pipeline for every action)
1. Load the instance and its pinned workflow version.
2. **Authorise**: the user has one of `action.roles` and is the assignee, or the step is a `ROLE_QUEUE` the user's role belongs to.
   The action's `when`, if present, must be true.
3. **Validate**: step field rules (`required` fields filled in, `readonly`/`hidden` fields unchanged), plus `remarks` and `attachment`.
4. Save the data changes, then run `action.handler` if set.
5. Run the current step's `onExit` events.
6. **Pick the next step**, first match wins:
   a. transitions with `from` = current step whose `on` is empty or equals the action code, ordered by `priority`
      (lower first), whose `when` is true or absent;
   b. else `action.to`;
   c. else the next step in array order.
7. Enter the next step: if `skipWhen` is true, record a `SKIPPED` history row and route again from it with no action
   (rules 6a, then 6c). Otherwise assign it, set `dueAt` from `slaHours`, and run its `onEnter` events.
8. Write a history row (action, from, to, actor, remarks, time spent in the step) in the same transaction.

## Step types
- `TASK` / `APPROVAL`: wait for an action. They behave the same; `APPROVAL` is only shown differently in the UI and reports.
- `AUTO`: on entry, run `handler`, then route on immediately (rules 6a, 6c).
- `PARALLEL`: open every step in `branches` at the same time. The instance has several current steps.
  Branch steps route to the group's `join` step.
- `JOIN`: `ALL` waits until every branch reaches it, `ANY` continues on the first and cancels the other open branches
  (with a history row for each).
- `SUBWORKFLOW`: start a child instance of `workflow` (latest published version), copying `copyFields`. The parent
  waits. When the child reaches an `END` step, the parent routes on and `data.$childOutcome` holds the child's outcome.
- `END`: closes the instance. No actions.

## Assignment
- `ROLE_QUEUE`: unassigned, and anyone with the role can claim it.
- `LEAST_LOADED`: the active user in the role with the fewest open tasks (ties broken by user id).
- `ROUND_ROBIN`: the next user in the role after the last one assigned for this step.
- `INITIATOR`: the user who started the instance.
- `FROM_FIELD`: the user in the `USER` field named in `field`.

## SLA
- A `@Scheduled` job every 5 minutes sends a reminder `remindBeforeHours` before `dueAt` (once), and on breach
  notifies `escalateTo` and adds that role to the task's allowed roles (once). Both are recorded in history.

## Expressions
- JSONLogic evaluated against `{ "data": {...}, "instance": { "status", "step", "createdAt" }, "user": { "id", "roles" } }`.
  Fields are read as `{"var": "data.category"}`.
- An evaluation error counts as `false` and is logged. It never throws to the user.

## Publish-time validator (cross-reference rules)
Reject a definition (with every error listed, not just the first) unless:
- all codes are unique in their scope (roles, entities, fields per entity, workflows, steps per workflow, actions per step);
- every referenced role, entity, field, step, workflow, status label and form exists;
- `startStep` exists, every step is reachable from it, and at least one `END` step is reachable;
- every non-`END` step has a way out (an action, a transition or a next step in order);
- each `PARALLEL` names a `JOIN` step, and all of its branches can reach that join;
- `SUBWORKFLOW` references do not form a cycle;
- at most one role is `public`, and only `public` forms are open to it.
