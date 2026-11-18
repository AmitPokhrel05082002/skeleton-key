import type { CreateUserRequest, UpdateUserRequest, UserView } from '../../../core/api/api.types';

/** Editable values of the user drawer. */
export interface UserFormValue {
  username: string;
  displayName: string;
  password: string;
  roles: string[];
  active: boolean;
}

export type UserFormErrors = Partial<Record<'username' | 'displayName' | 'password', string>>;

export const USERNAME_MIN = 3;
export const USERNAME_MAX = 64;
export const DISPLAY_NAME_MAX = 120;
export const PASSWORD_MIN_BYTES = 8;
export const PASSWORD_MAX_BYTES = 72;

/** Length of the UTF-8 encoding, the unit the backend limits passwords in. */
export function utf8Length(text: string): number {
  return new TextEncoder().encode(text).length;
}

/** Password rule of the backend (8 to 72 UTF-8 bytes). Returns an error text or null. */
export function validatePassword(password: string): string | null {
  const n = utf8Length(password);
  if (n < PASSWORD_MIN_BYTES) {
    return `Use at least ${PASSWORD_MIN_BYTES} characters.`;
  }
  if (n > PASSWORD_MAX_BYTES) {
    return `Use at most ${PASSWORD_MAX_BYTES} bytes.`;
  }
  return null;
}

/**
 * Same rules as the backend: username 3 to 64 characters without whitespace (create only),
 * display name 1 to 120 characters, password 8 to 72 bytes (create only).
 */
export function validateUserForm(value: UserFormValue, mode: 'create' | 'edit'): UserFormErrors {
  const errors: UserFormErrors = {};
  if (mode === 'create') {
    const u = value.username;
    if (u.length < USERNAME_MIN || u.length > USERNAME_MAX) {
      errors.username = `Use ${USERNAME_MIN} to ${USERNAME_MAX} characters.`;
    } else if (/\s/.test(u)) {
      errors.username = 'Spaces are not allowed.';
    }
    const pw = validatePassword(value.password);
    if (pw) {
      errors.password = pw;
    }
  }
  const name = value.displayName.trim();
  if (name.length < 1 || name.length > DISPLAY_NAME_MAX) {
    errors.displayName = `Use 1 to ${DISPLAY_NAME_MAX} characters.`;
  }
  return errors;
}

export function toCreateRequest(value: UserFormValue): CreateUserRequest {
  return {
    username: value.username,
    displayName: value.displayName.trim(),
    password: value.password,
    roles: [...value.roles],
    active: value.active,
  };
}

function sameSet(a: readonly string[], b: readonly string[]): boolean {
  const sa = new Set(a);
  const sb = new Set(b);
  if (sa.size !== sb.size) {
    return false;
  }
  for (const x of sa) {
    if (!sb.has(x)) {
      return false;
    }
  }
  return true;
}

/** Only the properties that differ from the original. Roles are compared as sets. */
export function toPatch(
  original: Pick<UserView, 'displayName' | 'roles' | 'active'>,
  edited: Pick<UserFormValue, 'displayName' | 'roles' | 'active'>,
): UpdateUserRequest {
  const patch: UpdateUserRequest = {};
  if (edited.displayName !== original.displayName) {
    patch.displayName = edited.displayName;
  }
  if (!sameSet(original.roles, edited.roles)) {
    patch.roles = [...new Set(edited.roles)];
  }
  if (edited.active !== original.active) {
    patch.active = edited.active;
  }
  return patch;
}

export interface MappedUserError {
  /** Message per form field (username, displayName, password, roles). */
  fields: Record<string, string>;
  /** Message for the drawer when it belongs to no field. */
  form: string | null;
}

const KNOWN_FIELDS = new Set(['username', 'displayName', 'password', 'roles', 'active']);

/** Maps 409 USERNAME_TAKEN, 422 violations and 409 SELF_LOCKOUT to field and form messages. */
export function mapUserError(err: {
  status: number;
  code: string;
  message: string;
  violations?: { field: string; rule: string; message?: string }[];
}): MappedUserError {
  const out: MappedUserError = { fields: {}, form: null };
  if (err.code === 'USERNAME_TAKEN') {
    out.fields['username'] = err.message || 'This username is already taken.';
    return out;
  }
  if (err.status === 422 && (err.violations?.length ?? 0) > 0) {
    const unmatched: string[] = [];
    for (const v of err.violations ?? []) {
      const key = v.field.replace(/\[.*$/, '');
      const text = v.message || v.rule;
      if (KNOWN_FIELDS.has(key)) {
        out.fields[key] = out.fields[key] ? `${out.fields[key]} ${text}` : text;
      } else {
        unmatched.push(text);
      }
    }
    out.form = unmatched.length > 0 ? unmatched.join(' ') : null;
    return out;
  }
  out.form = err.message || 'The request failed.';
  return out;
}
