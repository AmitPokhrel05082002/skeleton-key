import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { RuntimeConfigService } from '../config/runtime-config.service';
import { toApiError } from './api-error';
import {
  ActionBody,
  CreateUserRequest,
  HistoryEntry,
  InstanceView,
  ListQuery,
  LoginResponse,
  MeResponse,
  Page,
  PublicSubset,
  RecordSummary,
  RestrictedDefinition,
  RoleOption,
  SubmitResult,
  TrackRequest,
  TrackResponse,
  UpdateUserRequest,
  UserListQuery,
  UserPage,
  UserView,
  ValidatorReport,
  VersionRef,
} from './api.types';

const JSON_TEXT_HEADERS = new HttpHeaders({ 'Content-Type': 'application/json' });

/** Builds the list query parameters. Empty filter values are dropped. Exported for tests. */
export function buildListParams(q: ListQuery): HttpParams {
  let params = new HttpParams().set('page', String(q.page)).set('size', String(q.size)).set('view', q.view);
  if (q.sort) {
    params = params.set('sort', q.sort);
  }
  for (const [key, value] of Object.entries(q.filters ?? {})) {
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

/** The single place that talks to the Skeleton Backend. Errors are mapped to ApiError. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RuntimeConfigService);

  private get base(): string {
    return this.config.apiBase();
  }

  private seg(value: string): string {
    return encodeURIComponent(value);
  }

  private wrap<T>(source: Observable<T>): Observable<T> {
    return source.pipe(catchError((e: unknown) => throwError(() => toApiError(e))));
  }

  // auth
  login(username: string, password: string): Observable<LoginResponse> {
    return this.wrap(this.http.post<LoginResponse>(`${this.base}/auth/login`, { username, password }));
  }

  me(): Observable<MeResponse> {
    return this.wrap(this.http.get<MeResponse>(`${this.base}/auth/me`));
  }

  // public
  publicApp(app: string): Observable<PublicSubset> {
    return this.wrap(this.http.get<PublicSubset>(`${this.base}/public/apps/${this.seg(app)}`));
  }

  /** Public status lookup. No auth header (public URL). On 429 the error carries retryAfterSeconds. */
  track(app: string, body: TrackRequest): Observable<TrackResponse> {
    return this.http.post<TrackResponse>(`${this.base}/public/${this.seg(app)}/track`, body).pipe(
      catchError((e: unknown) => {
        const apiError = toApiError(e);
        if (e instanceof HttpErrorResponse && e.status === 429) {
          const raw = Number(e.headers.get('Retry-After'));
          apiError.retryAfterSeconds = Number.isFinite(raw) && raw >= 1 ? Math.ceil(raw) : 60;
        }
        return throwError(() => apiError);
      }),
    );
  }

  // admin users
  listUsers(q: UserListQuery): Observable<UserPage> {
    let params = new HttpParams().set('page', String(q.page)).set('size', String(q.size));
    if (q.q && q.q.trim() !== '') {
      params = params.set('q', q.q.trim());
    }
    if (q.role) {
      params = params.set('role', q.role);
    }
    if (q.active !== undefined) {
      params = params.set('active', String(q.active));
    }
    if (q.sort) {
      params = params.set('sort', q.sort);
    }
    return this.wrap(this.http.get<UserPage>(`${this.base}/admin/users`, { params }));
  }

  getUser(id: string): Observable<UserView> {
    return this.wrap(this.http.get<UserView>(`${this.base}/admin/users/${this.seg(id)}`));
  }

  createUser(body: CreateUserRequest): Observable<UserView> {
    return this.wrap(this.http.post<UserView>(`${this.base}/admin/users`, body));
  }

  updateUser(id: string, patch: UpdateUserRequest): Observable<UserView> {
    return this.wrap(this.http.patch<UserView>(`${this.base}/admin/users/${this.seg(id)}`, patch));
  }

  resetPassword(id: string, password: string): Observable<void> {
    return this.wrap(this.http.post<void>(`${this.base}/admin/users/${this.seg(id)}/password`, { password }));
  }

  roleOptions(): Observable<RoleOption[]> {
    return this.wrap(this.http.get<RoleOption[]>(`${this.base}/admin/users/role-options`));
  }

  // definition
  definition(app: string, version?: number): Observable<RestrictedDefinition> {
    let params = new HttpParams();
    if (version !== undefined) {
      params = params.set('version', String(version));
    }
    return this.wrap(
      this.http.get<RestrictedDefinition>(`${this.base}/apps/${this.seg(app)}/definition`, { params }),
    );
  }

  // submit
  submitForm(app: string, form: string, body: Record<string, unknown>): Observable<SubmitResult> {
    return this.wrap(
      this.http.post<SubmitResult>(`${this.base}/apps/${this.seg(app)}/forms/${this.seg(form)}/submit`, body),
    );
  }

  // records
  listRecords(app: string, entity: string, q: ListQuery): Observable<Page<RecordSummary>> {
    return this.wrap(
      this.http.get<Page<RecordSummary>>(
        `${this.base}/apps/${this.seg(app)}/entities/${this.seg(entity)}/records`,
        { params: buildListParams(q) },
      ),
    );
  }

  getRecord(app: string, entity: string, id: string): Observable<RecordSummary> {
    return this.wrap(
      this.http.get<RecordSummary>(
        `${this.base}/apps/${this.seg(app)}/entities/${this.seg(entity)}/records/${this.seg(id)}`,
      ),
    );
  }

  // instances
  getInstance(id: string): Observable<InstanceView> {
    return this.wrap(this.http.get<InstanceView>(`${this.base}/instances/${this.seg(id)}`));
  }

  runAction(id: string, code: string, body: ActionBody): Observable<InstanceView> {
    return this.wrap(
      this.http.post<InstanceView>(`${this.base}/instances/${this.seg(id)}/actions/${this.seg(code)}`, body),
    );
  }

  history(id: string): Observable<HistoryEntry[]> {
    return this.wrap(this.http.get<HistoryEntry[]>(`${this.base}/instances/${this.seg(id)}/history`));
  }

  // admin definitions
  definitionApps(): Observable<string[]> {
    return this.wrap(this.http.get<string[]>(`${this.base}/definitions/apps`));
  }

  definitionVersions(app: string): Observable<VersionRef[]> {
    return this.wrap(this.http.get<VersionRef[]>(`${this.base}/apps/${this.seg(app)}/definitions`));
  }

  /** Sends the raw editor text so the backend can report line and column of malformed JSON. */
  validateDefinition(text: string): Observable<ValidatorReport> {
    return this.wrap(
      this.http.post<ValidatorReport>(`${this.base}/definitions/validate`, text, { headers: JSON_TEXT_HEADERS }),
    );
  }

  /** Sends the raw editor text so the backend can report line and column of malformed JSON. */
  createDefinition(text: string): Observable<VersionRef> {
    return this.wrap(
      this.http.post<VersionRef>(`${this.base}/definitions`, text, { headers: JSON_TEXT_HEADERS }),
    );
  }

  updateDefinition(id: string, text: string): Observable<VersionRef> {
    return this.wrap(
      this.http.put<VersionRef>(`${this.base}/definitions/${this.seg(id)}`, text, { headers: JSON_TEXT_HEADERS }),
    );
  }

  editPublished(app: string, base: string, text: string): Observable<VersionRef> {
    return this.wrap(
      this.http.post<VersionRef>(
        `${this.base}/apps/${this.seg(app)}/definitions/${this.seg(base)}/edit`,
        text,
        { headers: JSON_TEXT_HEADERS },
      ),
    );
  }

  /** Returns the stored definition document as raw JSON text. */
  getDefinition(id: string): Observable<string> {
    return this.wrap(
      this.http.get(`${this.base}/definitions/${this.seg(id)}`, { responseType: 'text' }),
    );
  }

  definitionInfo(id: string): Observable<VersionRef> {
    return this.wrap(this.http.get<VersionRef>(`${this.base}/definitions/${this.seg(id)}/info`));
  }

  publish(id: string): Observable<VersionRef> {
    return this.wrap(this.http.post<VersionRef>(`${this.base}/definitions/${this.seg(id)}/publish`, {}));
  }
}
