export const WARNING_WINDOW_MS = 24 * 3600 * 1000;

export type SlaState = 'NONE' | 'DUE_SOON' | 'OVERDUE';

/**
 * Classifies a due date. Ended records and missing or unparseable dates give NONE.
 * OVERDUE when now is later than dueAt; DUE_SOON when the remaining time is within windowMs.
 */
export function slaState(
  dueAt: string | null | undefined,
  now: Date,
  windowMs: number,
  ended: boolean,
): SlaState {
  if (ended || !dueAt) {
    return 'NONE';
  }
  const due = Date.parse(dueAt);
  const current = now.getTime();
  if (Number.isNaN(due) || Number.isNaN(current)) {
    return 'NONE';
  }
  if (current > due) {
    return 'OVERDUE';
  }
  return due - current <= windowMs ? 'DUE_SOON' : 'NONE';
}
