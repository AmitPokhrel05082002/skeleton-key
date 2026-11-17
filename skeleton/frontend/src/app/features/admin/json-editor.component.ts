import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { Observable, Subject, catchError, debounceTime, forkJoin, map, of, switchMap } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { ApiError, ValidatorReport, VersionRef, Violation } from '../../core/api/api.types';
import {
  EditorState,
  canPublish,
  chooseSave,
  formatJson,
  jsonState,
  lineColumnToOffset,
  minimalDefinitionTemplate,
  pointerToOffset,
} from './json-editor-helpers';
import { HasUnsavedChanges } from './unsaved-changes.guard';

/** Debounce before the text is sent to `validateDefinition` (Requirement 14.3). */
export const VALIDATE_DEBOUNCE_MS = 500;

/** Outcome of one validation run: a report, a backend error, or nothing to show. */
type ValidationResult = { report: ValidatorReport } | { error: ApiError } | null;

/**
 * Definition JSON editor (Requirement 14).
 *
 * Extension point for the form builder (task 16.8): the whole editor content is the `text` signal (read it
 * with `text()`), and `setText(text)` replaces it as if the user had typed it, which marks the editor dirty and
 * restarts the debounced validation. A builder panel placed beside the editor (for example inside a wrapper
 * page component that also hosts `<sk-json-editor>` and reaches it through `viewChild`) needs nothing else.
 */
@Component({
  selector: 'sk-json-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzAlertModule, NzButtonModule, NzSpinModule, NzTagModule],
  template: `
    <div class="sk-toolbar">
      <h2>Definition editor</h2>
      <span class="sk-version-ref">
        @if (saved(); as ref) {
          <nz-tag [nzColor]="ref.status === 'PUBLISHED' ? 'green' : 'orange'">{{ ref.status }}</nz-tag>
          <span class="sk-ref-version">v{{ ref.version }}</span>
          <code class="sk-ref-id">{{ ref.id }}</code>
        } @else {
          <nz-tag>NEW</nz-tag>
        }
        @if (dirty()) {
          <span class="sk-dirty">unsaved changes</span>
        }
      </span>
      <span class="sk-actions">
        <button
          nz-button
          nzType="primary"
          type="button"
          class="sk-save"
          [disabled]="!canSave()"
          (click)="save()"
        >
          Save
        </button>
        <button nz-button type="button" class="sk-publish" [disabled]="!publishEnabled()" (click)="publish()">
          Publish
        </button>
      </span>
    </div>

    @if (loading()) {
      <nz-spin nzSimple />
    }
    @if (loadError(); as err) {
      <nz-alert nzType="error" [nzMessage]="err" role="alert" />
    }
    @if (notice(); as n) {
      <nz-alert class="sk-notice" [nzType]="n.type" [nzMessage]="n.text" role="alert" />
    }

    <div class="sk-editor-main">
      <textarea
        #area
        class="sk-json-text"
        spellcheck="false"
        aria-label="Definition JSON"
        [value]="text()"
        (input)="onInput($event)"
      ></textarea>

      @if (parse(); as p) {
        @if (!p.valid) {
          <nz-alert class="sk-parse-error" nzType="error" [nzMessage]="p.error ?? 'Invalid JSON'" role="alert" />
        }
      }
      @if (backendError(); as be) {
        <nz-alert
          class="sk-backend-error"
          nzType="error"
          [nzMessage]="be.message"
          [nzDescription]="locationText()"
          role="alert"
        />
      }
    </div>

    <section class="sk-report" aria-label="Validator report">
      @if (validating()) {
        <nz-spin nzSimple />
      }
      @if (report().length > 0) {
        <ul class="sk-violations">
          @for (v of report(); track $index) {
            <li>
              <button type="button" class="sk-violation" (click)="goTo(v)">
                <code class="sk-v-field">{{ v.field || '(root)' }}</code>
                <span class="sk-v-rule">{{ v.rule }}</span>
                <span class="sk-v-message">{{ v.message }}</span>
              </button>
            </li>
          }
        </ul>
      } @else if (reportShown() && !validating()) {
        <p class="sk-report-ok">No validation errors.</p>
      }
    </section>
  `,
  styles: `
    .sk-toolbar {
      display: flex;
      align-items: center;
      gap: 16px;
      flex-wrap: wrap;
    }
    .sk-actions {
      margin-left: auto;
      display: flex;
      gap: 8px;
    }
    .sk-dirty {
      margin-left: 8px;
      color: var(--sk-warn-fg);
    }
    .sk-json-text {
      width: 100%;
      min-height: 24rem;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 13px;
      line-height: 1.45;
      tab-size: 2;
      white-space: pre;
      padding: 8px;
      box-sizing: border-box;
      background: var(--sk-code-bg);
      color: var(--sk-code-fg);
      caret-color: var(--sk-code-caret);
      border: 1px solid var(--sk-code-border);
      border-radius: 6px;
    }
    .sk-json-text:focus,
    .sk-json-text:focus-visible {
      border-color: var(--sk-primary, var(--sk-accent));
    }
    .sk-json-text::selection {
      background: var(--sk-code-selection-bg);
      color: var(--sk-code-selection-fg);
    }
    .sk-violations {
      list-style: none;
      padding: 0;
    }
    .sk-violation {
      display: flex;
      gap: 12px;
      width: 100%;
      text-align: left;
      background: none;
      border: 0;
      border-bottom: 1px solid var(--sk-divider);
      color: inherit;
      padding: 6px 4px;
      cursor: pointer;
    }
  `,
})
export class JsonEditorComponent implements HasUnsavedChanges {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly message = inject(NzMessageService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly area = viewChild<ElementRef<HTMLTextAreaElement>>('area');

  private readonly textSig = signal('');
  /** The editor content. Read-only view for panels beside the editor (form builder). */
  readonly text = this.textSig.asReadonly();

  /** Text as last loaded or saved; `dirty` compares against it. */
  private readonly baseline = signal('');
  protected readonly saved = signal<VersionRef | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly validating = signal(false);
  protected readonly reportShown = signal(false);
  protected readonly report = signal<Violation[]>([]);
  protected readonly backendError = signal<ApiError | null>(null);
  protected readonly notice = signal<{ type: 'success' | 'error' | 'warning'; text: string } | null>(null);

  protected readonly parse = computed(() => jsonState(this.textSig()));

  /** True when unsaved changes exist, or the definition was never saved (Requirement 14.12 state). */
  protected readonly dirty = computed(() => this.saved() === null || this.textSig() !== this.baseline());
  private readonly state = computed<EditorState>(() => ({ saved: this.saved(), dirty: this.dirty() }));

  protected readonly canSave = computed(() => this.parse().valid && !this.busy() && !this.loading());
  protected readonly publishEnabled = computed(() => canPublish(this.state()) && !this.busy() && !this.loading());
  protected readonly locationText = computed(() => {
    const e = this.backendError();
    return e?.line !== undefined && e.column !== undefined ? `Line ${e.line}, column ${e.column}` : '';
  });

  private readonly validate$ = new Subject<string>();

  constructor() {
    this.validate$
      .pipe(
        debounceTime(VALIDATE_DEBOUNCE_MS),
        switchMap((text): Observable<ValidationResult> => {
          if (!jsonState(text).valid) {
            this.validating.set(false);
            return of(null);
          }
          this.validating.set(true);
          return this.api.validateDefinition(text).pipe(
            map((report): ValidationResult => ({ report })),
            catchError((error: ApiError) => of<ValidationResult>({ error })),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => this.showValidation(result));

    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('id');
      if (id === null) {
        this.openNew();
      } else if (this.saved()?.id !== id) {
        this.load(id);
      }
    });
  }

  /** Replaces the editor content as if typed: marks it changed and restarts the debounced validation. */
  setText(text: string): void {
    this.textSig.set(text);
    this.backendError.set(null);
    this.validate$.next(text);
  }

  hasUnsavedChanges(): boolean {
    return this.textSig() !== this.baseline();
  }

  protected onInput(event: Event): void {
    this.setText((event.target as HTMLTextAreaElement).value);
  }

  /** Moves the caret to the location a violation points at, when it can be found. */
  protected goTo(v: Violation): void {
    const offset = pointerToOffset(this.textSig(), v.field);
    if (offset !== null) this.placeCaret(offset);
  }

  protected save(): void {
    if (!this.canSave()) return;
    const text = this.textSig();
    const state = this.state();
    const ref = state.saved;
    let call: Observable<VersionRef>;
    switch (chooseSave(state)) {
      case 'POST':
        call = this.api.createDefinition(text);
        break;
      case 'PUT':
        call = this.api.updateDefinition((ref as VersionRef).id, text);
        break;
      default:
        call = this.api.editPublished(this.appCode(ref as VersionRef, text), String((ref as VersionRef).version), text);
    }
    this.run(call, (result) => {
      this.adopt(result, text);
      this.report.set([]);
      this.reportShown.set(false);
      this.message.success(`Saved as ${result.status} version ${result.version}.`);
    });
  }

  protected publish(): void {
    const ref = this.saved();
    if (!this.publishEnabled() || !ref) return;
    this.run(this.api.publish(ref.id), (result) => {
      this.adopt(result, this.textSig());
      this.message.success(`Version ${result.version} is published.`);
    });
  }

  // ---------------------------------------------------------------------------------------------

  private appCode(ref: VersionRef, text: string): string {
    if (ref.app) return ref.app;
    const value = jsonState(text).value as { app?: { code?: unknown } } | undefined;
    return typeof value?.app?.code === 'string' ? value.app.code : '';
  }

  private run(call: Observable<VersionRef>, onSuccess: (ref: VersionRef) => void): void {
    this.busy.set(true);
    this.notice.set(null);
    this.backendError.set(null);
    call.subscribe({
      next: (ref) => {
        this.busy.set(false);
        onSuccess(ref);
      },
      error: (e: ApiError) => {
        this.busy.set(false);
        this.fail(e);
      },
    });
  }

  /** Makes the stored version the editor state (clean) and moves the URL to its id. */
  private adopt(ref: VersionRef, text: string): void {
    this.saved.set(ref);
    this.baseline.set(text);
    if (this.route.snapshot.paramMap.get('id') !== ref.id) {
      void this.router.navigate(['/admin/editor', ref.id], { replaceUrl: true });
    }
  }

  private fail(e: ApiError): void {
    if (e.status === 422 && e.violations?.length) {
      this.report.set(e.violations);
      this.reportShown.set(true);
      this.notice.set({ type: 'error', text: e.message || 'The definition has validation errors.' });
      return;
    }
    if (e.status === 400) {
      this.showBackendError(e);
      return;
    }
    if (e.status === 403) {
      this.notice.set({ type: 'error', text: 'Not authorised' });
      return;
    }
    this.notice.set({ type: 'error', text: e.message || 'The request failed.' });
  }

  private showBackendError(e: ApiError): void {
    this.backendError.set(e);
    if (e.line !== undefined && e.column !== undefined) {
      this.placeCaret(lineColumnToOffset(this.textSig(), e.line, e.column));
    }
  }

  private showValidation(result: ValidationResult): void {
    this.validating.set(false);
    if (result === null) {
      this.report.set([]);
      this.reportShown.set(false);
    } else if ('report' in result) {
      this.report.set(result.report);
      this.reportShown.set(true);
    } else if (result.error.status === 422 && result.error.violations?.length) {
      this.report.set(result.error.violations);
      this.reportShown.set(true);
    } else if (result.error.status === 400) {
      this.report.set([]);
      this.showBackendError(result.error);
    } else {
      this.notice.set({ type: 'error', text: result.error.message || 'Validation failed.' });
    }
  }

  private openNew(): void {
    const text = formatJson(minimalDefinitionTemplate());
    this.saved.set(null);
    this.baseline.set(text);
    this.textSig.set(text);
    this.validate$.next(text);
  }

  private load(id: string): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({ text: this.api.getDefinition(id), info: this.api.definitionInfo(id) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ text, info }) => {
          const parsed = jsonState(text);
          const shown = parsed.valid ? formatJson(parsed.value) : text;
          this.saved.set(info);
          this.baseline.set(shown);
          this.textSig.set(shown);
          this.loading.set(false);
          this.validate$.next(shown);
        },
        error: (e: ApiError) => {
          this.loading.set(false);
          this.loadError.set(e.status === 403 ? 'Not authorised' : e.message || 'Could not load the definition.');
        },
      });
  }

  private placeCaret(offset: number): void {
    const el = this.area()?.nativeElement;
    if (!el) return;
    el.focus();
    el.setSelectionRange(offset, offset);
  }
}
