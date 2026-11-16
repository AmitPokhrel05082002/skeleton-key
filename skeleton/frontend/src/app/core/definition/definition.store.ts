import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { toApiError } from '../api/api-error';
import { ApiError, FormDef, RestrictedDefinition } from '../api/api.types';
import { ADMIN_ROLE } from '../auth/session.service';

export type DefinitionStatus = 'idle' | 'loading' | 'ready' | 'unavailable' | 'error';

export type MenuKind = 'view' | 'new' | 'admin';

export interface MenuItem {
  key: string;
  kind: MenuKind;
  label: string;
  /** Absolute router commands. */
  link: string[];
}

/** True when the user may submit the form: it is public, or one of its roles is held by the user. */
export function canSubmitForm(form: FormDef, roles: readonly string[]): boolean {
  if (form.public === true) {
    return true;
  }
  return (form.roles ?? []).some((r) => roles.includes(r));
}

/**
 * Pure. Menu in order: one entry per view (label or code), one "New" entry per submittable form,
 * then "Admin" for a platform admin.
 */
export function buildMenu(def: RestrictedDefinition, roles: readonly string[]): MenuItem[] {
  const app = def.app.code;
  const items: MenuItem[] = [];
  for (const v of def.views ?? []) {
    items.push({
      key: 'view:' + v.code,
      kind: 'view',
      label: v.label || v.code,
      link: ['/app', app, 'views', v.code],
    });
  }
  for (const f of def.forms ?? []) {
    if (canSubmitForm(f, roles)) {
      items.push({
        key: 'new:' + f.code,
        kind: 'new',
        label: 'New ' + (f.label || f.code),
        link: ['/app', app, 'forms', f.code, 'new'],
      });
    }
  }
  if (roles.includes(ADMIN_ROLE)) {
    items.push({ key: 'admin', kind: 'admin', label: 'Admin', link: ['/admin'] });
  }
  return items;
}

/** Holds the Restricted_Definition of the app currently open in the shell. */
@Injectable({ providedIn: 'root' })
export class DefinitionStore {
  private readonly api = inject(ApiService);
  private requestId = 0;

  private readonly appState = signal<string | null>(null);
  private readonly statusState = signal<DefinitionStatus>('idle');
  private readonly defState = signal<RestrictedDefinition | null>(null);
  private readonly errorState = signal<ApiError | null>(null);

  readonly app = this.appState.asReadonly();
  readonly status = this.statusState.asReadonly();
  readonly definition = this.defState.asReadonly();
  readonly error = this.errorState.asReadonly();
  readonly loading = computed(() => this.statusState() === 'loading');

  /** Loads the definition of an app. Reuses the loaded one for the same app unless force is set. */
  load(app: string, force = false): void {
    if (!force && this.appState() === app && (this.statusState() === 'ready' || this.statusState() === 'loading')) {
      return;
    }
    const id = ++this.requestId;
    this.appState.set(app);
    this.statusState.set('loading');
    this.defState.set(null);
    this.errorState.set(null);
    this.api.definition(app).subscribe({
      next: (def) => {
        if (id !== this.requestId) {
          return;
        }
        this.defState.set(def);
        this.statusState.set('ready');
      },
      error: (e: unknown) => {
        if (id !== this.requestId) {
          return;
        }
        const err = toApiError(e);
        this.errorState.set(err);
        this.statusState.set(err.status === 404 || err.status === 409 ? 'unavailable' : 'error');
      },
    });
  }

  reset(): void {
    this.requestId++;
    this.appState.set(null);
    this.statusState.set('idle');
    this.defState.set(null);
    this.errorState.set(null);
  }
}
