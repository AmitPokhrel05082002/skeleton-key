import type { TrackResponse, TrackStatus } from '../../core/api/api.types';

export const REFERENCE_MAX = 128;
export const EMAIL_MAX = 254;

export interface TrackFormErrors {
  reference?: string;
  email?: string;
}

export interface TrackFormResult {
  valid: boolean;
  errors: TrackFormErrors;
}

const EMAIL_SHAPE = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/;

/** Valid iff the trimmed reference is 1..128 characters and the trimmed email is a plausible address of at most 254 characters. */
export function validateTrackForm(reference: string, email: string): TrackFormResult {
  const errors: TrackFormErrors = {};
  const ref = reference.trim();
  if (ref.length === 0) {
    errors.reference = 'Enter the reference number.';
  } else if (ref.length > REFERENCE_MAX) {
    errors.reference = `Use at most ${REFERENCE_MAX} characters.`;
  }
  const mail = email.trim();
  if (mail.length === 0) {
    errors.email = 'Enter the email address.';
  } else if (mail.length > EMAIL_MAX || !EMAIL_SHAPE.test(mail)) {
    errors.email = 'Enter a valid email address.';
  }
  return { valid: errors.reference === undefined && errors.email === undefined, errors };
}

/** NG-ZORRO preset colour per status category, used when the status has no colour of its own. */
const CATEGORY_COLOR: Record<string, string> = {
  OPEN: 'blue',
  IN_PROGRESS: 'gold',
  DONE: 'green',
  REJECTED: 'red',
};

/** The status colour when it is `#rrggbb`, else the category colour, else `default`. Never throws. */
export function statusChipColor(status: Pick<TrackStatus, 'color' | 'category'> | null | undefined): string {
  const color = status?.color;
  if (typeof color === 'string' && /^#[0-9a-fA-F]{6}$/.test(color)) {
    return color;
  }
  const category = status?.category;
  if (typeof category === 'string' && Object.hasOwn(CATEGORY_COLOR, category)) {
    return CATEGORY_COLOR[category];
  }
  return 'default';
}

/** Timeline entries in the order received, exposing only `at` and `status`. */
export function toTimelineItems(response: Pick<TrackResponse, 'timeline'>): { at: string; status: string }[] {
  return (response.timeline ?? []).map((e) => ({ at: e.at, status: e.status }));
}
