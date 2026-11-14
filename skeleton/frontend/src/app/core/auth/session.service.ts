import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap, switchMap } from 'rxjs';
import { ApiService } from '../api/api.service';

/** The only app-specific code constant of the frontend: the platform admin role. */
export const ADMIN_ROLE = 'PLATFORM_ADMIN';

export const SESSION_STORAGE_KEY = 'sk.session';

export interface Session {
  token: string;
  expiresAt: number;
  userId: string;
  displayName: string;
  roles: string[];
}

/**
 * Parses a stored session. Never throws. Returns null for malformed or expired values.
 * @param text raw sessionStorage value
 * @param now current time in epoch milliseconds
 */
export function parseStoredSession(text: string | null | undefined, now: number): Session | null {
  if (typeof text !== 'string' || text === '') {
    return null;
  }
  try {
    const v: unknown = JSON.parse(text);
    if (typeof v !== 'object' || v === null || Array.isArray(v)) {
      return null;
    }
    const o = v as Record<string, unknown>;
    if (
      typeof o['token'] !== 'string' ||
      o['token'] === '' ||
      typeof o['expiresAt'] !== 'number' ||
      !Number.isFinite(o['expiresAt']) ||
      typeof o['userId'] !== 'string' ||
      typeof o['displayName'] !== 'string' ||
      !Array.isArray(o['roles']) ||
      !o['roles'].every((r) => typeof r === 'string')
    ) {
      return null;
    }
    if (o['expiresAt'] <= now) {
      return null;
    }
    return {
      token: o['token'],
      expiresAt: o['expiresAt'],
      userId: o['userId'],
      displayName: o['displayName'],
      roles: [...(o['roles'] as string[])],
    };
  } catch {
    return null;
  }
}

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly api = inject(ApiService);
  private readonly state = signal<Session | null>(null);

  readonly session = this.state.asReadonly();
  readonly roles = computed(() => this.state()?.roles ?? []);
  readonly isPlatformAdmin = computed(() => this.roles().includes(ADMIN_ROLE));

  /** POST login, store the token, then GET /auth/me and overwrite id, name and roles. */
  login(username: string, password: string): Observable<Session> {
    return this.api.login(username, password).pipe(
      tap((res) =>
        // Stored first so the interceptor can add the token to the /auth/me request.
        this.set({
          token: res.token,
          expiresAt: Date.now() + res.expiresInSeconds * 1000,
          userId: res.userId,
          displayName: res.displayName,
          roles: res.roles ?? [],
        }),
      ),
      switchMap(() => this.refreshFromMe()),
    );
  }

  /** Restores a stored, unexpired session and refreshes the user from /auth/me. Never errors. */
  restore(): Observable<void> {
    const stored = parseStoredSession(this.read(), Date.now());
    if (!stored) {
      this.clear();
      return of(undefined);
    }
    this.state.set(stored);
    return this.refreshFromMe().pipe(
      map(() => undefined),
      catchError(() => {
        this.clear();
        return of(undefined);
      }),
    );
  }

  clear(): void {
    this.state.set(null);
    try {
      sessionStorage.removeItem(SESSION_STORAGE_KEY);
    } catch {
      // storage unavailable
    }
  }

  private refreshFromMe(): Observable<Session> {
    return this.api.me().pipe(
      map((me) => {
        const current = this.state();
        if (!current) {
          throw new Error('No session');
        }
        const next: Session = { ...current, userId: me.userId, displayName: me.displayName, roles: me.roles ?? [] };
        this.set(next);
        return next;
      }),
      catchError((e: unknown) => {
        this.clear();
        throw e;
      }),
    );
  }

  private set(session: Session): void {
    this.state.set(session);
    try {
      sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
    } catch {
      // storage unavailable: the session stays in memory only
    }
  }

  private read(): string | null {
    try {
      return sessionStorage.getItem(SESSION_STORAGE_KEY);
    } catch {
      return null;
    }
  }
}
