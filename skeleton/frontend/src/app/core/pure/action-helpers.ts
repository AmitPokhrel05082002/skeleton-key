import type { ActionBody, ActionInfo, FieldRule } from '../api/api.types';

function isBlank(v: string | null | undefined): boolean {
  return v == null || v.trim() === '';
}

/** True when every input whose action rule is `required` is non-blank. */
export function actionDialogReady(
  action: Pick<ActionInfo, 'remarks' | 'attachment'>,
  remarks: string | null | undefined,
  attachment: string | null | undefined,
): boolean {
  if (action.remarks === 'required' && isBlank(remarks)) return false;
  if (action.attachment === 'required' && isBlank(attachment)) return false;
  return true;
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const ka = Object.keys(a);
  const kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return ka.every(
    (k) =>
      Object.prototype.hasOwnProperty.call(b, k) &&
      deepEqual((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]),
  );
}

/**
 * Builds the action request body: only editable fields (rule `optional` or `required`)
 * whose value differs from the initial one; `remarks` and `attachment` only when non-blank.
 */
export function buildActionBody(
  initial: Record<string, unknown>,
  current: Record<string, unknown>,
  rules: Record<string, FieldRule>,
  remarks?: string | null,
  attachment?: string | null,
): ActionBody {
  const fields: Record<string, unknown> = {};
  for (const code of Object.keys(current)) {
    if (!Object.prototype.hasOwnProperty.call(rules, code)) continue;
    const rule = rules[code];
    if (rule !== 'optional' && rule !== 'required') continue;
    const before = Object.prototype.hasOwnProperty.call(initial, code) ? initial[code] : undefined;
    if (!deepEqual(before, current[code])) fields[code] = current[code];
  }
  const body: ActionBody = { fields };
  if (!isBlank(remarks)) body.remarks = remarks as string;
  if (!isBlank(attachment)) body.attachment = attachment as string;
  return body;
}
