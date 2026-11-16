import type { ApiError, Violation } from '../api/api.types';
import { RULE_MESSAGES } from './rule-messages';

export interface MappedErrors {
  /** One key per control name, possibly an empty list. */
  byControl: Record<string, string[]>;
  /** Messages of violations whose field matches no control. */
  unmatched: string[];
}

const FALLBACK = 'Invalid value';

function isBlank(s: string | undefined | null): boolean {
  return s == null || s.trim() === '';
}

/** message, else lookup table, else the rule text, else 'Invalid value'. Never empty. */
export function violationMessage(v: Violation): string {
  if (!isBlank(v.message)) {
    return v.message as string;
  }
  if (!isBlank(v.rule)) {
    return Object.hasOwn(RULE_MESSAGES, v.rule) ? RULE_MESSAGES[v.rule] : v.rule;
  }
  return FALLBACK;
}

/** Assigns every violation exactly once, keeping order. */
export function mapErrors(err: ApiError, controlNames: readonly string[]): MappedErrors {
  const byControl: Record<string, string[]> = Object.create(null) as Record<string, string[]>;
  for (const name of controlNames) {
    byControl[name] = [];
  }
  const unmatched: string[] = [];
  for (const v of err.violations ?? []) {
    const message = violationMessage(v);
    const list = Object.hasOwn(byControl, v.field) ? byControl[v.field] : undefined;
    if (list) {
      list.push(message);
    } else {
      unmatched.push(message);
    }
  }
  return { byControl, unmatched };
}
