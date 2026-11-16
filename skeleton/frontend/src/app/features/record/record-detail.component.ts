import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
  viewChild,
  type OnInit,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { forkJoin, of, switchMap } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import type {
  ActionInfo,
  ApiError,
  EntityDef,
  HistoryEntry,
  InstanceView,
  RecordSummary,
  Violation,
} from '../../core/api/api.types';
import { DefinitionStore } from '../../core/definition/definition.store';
import { buildActionBody } from '../../core/pure/action-helpers';
import { displayName } from '../../core/pure/display-name';
import { errorText } from '../../core/pure/error-text';
import { WARNING_WINDOW_MS, slaState, type SlaState } from '../../core/pure/sla';
import { ErrorAlertComponent } from '../../shared/error-alert/error-alert.component';
import { SlaBadgeComponent } from '../../shared/sla-badge/sla-badge.component';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';
import { DynamicFormComponent } from '../form/dynamic-form.component';
import { ActionDialogComponent, type ActionDialogResult } from './action-dialog.component';
import { HistoryTimelineComponent } from './history-timeline.component';

/** Fields of an action error that belong to the action dialog instead of the form. */
const DIALOG_FIELDS: readonly string[] = ['remarks', 'attachment'];

/** Route `/app/:app/records/:entity/:id`: one record, its workflow actions and its history. */
@Component({
  selector: 'sk-record-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DynamicFormComponent,
    ActionDialogComponent,
    HistoryTimelineComponent,
    ErrorAlertComponent,
    SlaBadgeComponent,
    StatusChipComponent,
    NzButtonModule,
    NzModalModule,
    NzSpinModule,
    NzTagModule,
    RouterLink,
  ],
  template: `
    @if (notFound()) {
      <main class="sk-record-notfound">
        <h1>Not found</h1>
        <p>This record does not exist or you cannot see it.</p>
        <a nz-button nzType="primary" [routerLink]="['/app', app()]">Back to app home</a>
      </main>
    } @else if (loadError(); as err) {
      <sk-error-alert [error]="err" (retry)="load()" />
    } @else if (typeMissing()) {
      <main class="sk-record-notfound">
        <h1>Not found</h1>
        <p>This record type is not available.</p>
        <a nz-button nzType="primary" [routerLink]="['/app', app()]">Back to app home</a>
      </main>
    } @else if (loading() || !record() || !entity()) {
      <nz-spin nzSimple />
    } @else {
      @if (record(); as rec) {
        <header class="sk-record-header">
          <h1 class="sk-record-reference">{{ rec.referenceNo }}</h1>
          <dl class="sk-record-meta">
            @if (instance(); as inst) {
              <dt>Step</dt>
              <dd class="sk-record-step">{{ rec.stepLabel || inst.step }}</dd>
              <dt>Status</dt>
              <dd>
                <sk-status-chip [label]="inst.status" [color]="rec.statusColor" />
                @if (inst.ended) {
                  <nz-tag class="sk-record-closed">Closed</nz-tag>
                }
              </dd>
              <dt>Assignee</dt>
              <dd class="sk-record-assignee">{{ assigneeText() }}</dd>
              @if (dueAt(); as due) {
                <dt>Due</dt>
                <dd class="sk-record-due">
                  {{ due }}
                  <sk-sla-badge [state]="sla()" />
                </dd>
              }
            }
          </dl>
        </header>

        <sk-dynamic-form
          [fields]="fields()"
          [rules]="instance()?.fieldRules"
          [initial]="rec.data"
          [disabled]="formDisabled()"
          [serverError]="formError()"
        />

        @if (instance(); as inst) {
          @if (!inst.ended && inst.actions.length > 0) {
            <div class="sk-record-actions">
              @for (a of inst.actions; track a.code) {
                <button
                  nz-button
                  type="button"
                  class="sk-action-btn"
                  [attr.data-action]="a.code"
                  [nzType]="a.style === 'primary' ? 'primary' : 'default'"
                  [nzDanger]="a.style === 'danger'"
                  [disabled]="pending()"
                  (click)="onAction(a)"
                >
                  {{ a.label }}
                </button>
              }
            </div>
          }
        }

        @if (openAction(); as a) {
          <sk-action-dialog
            [action]="a"
            [pending]="pending()"
            [violations]="dialogViolations()"
            (confirmed)="run(a, $event)"
            (cancelled)="closeDialog()"
          />
        }

        @if (instance() && history().length > 0) {
          <section class="sk-record-history">
            <h2>History</h2>
            <sk-history-timeline [entries]="history()" />
          </section>
        }
      }
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 960px;
    }
    .sk-record-meta {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 4px 16px;
      margin: 0 0 16px;
    }
    .sk-record-meta dt {
      font-weight: 600;
    }
    .sk-record-meta dd {
      margin: 0;
    }
    .sk-record-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 16px 0;
    }
    .sk-record-notfound {
      max-width: 40rem;
      margin: 4rem auto;
    }
  `,
})
export class RecordDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ApiService);
  private readonly message = inject(NzMessageService);
  private readonly modal = inject(NzModalService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly store = inject(DefinitionStore);

  protected readonly app = signal(
    this.route.parent?.snapshot.paramMap.get('app') ?? this.route.snapshot.paramMap.get('app') ?? '',
  );
  private readonly entityCode = this.route.snapshot.paramMap.get('entity') ?? '';
  private readonly recordId = this.route.snapshot.paramMap.get('id') ?? '';

  protected readonly record = signal<RecordSummary | null>(null);
  protected readonly instance = signal<InstanceView | null>(null);
  protected readonly history = signal<HistoryEntry[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly notFound = signal(false);

  protected readonly pending = signal(false);
  protected readonly openAction = signal<ActionInfo | null>(null);
  private readonly actionError = signal<ApiError | null>(null);

  private readonly form = viewChild(DynamicFormComponent);

  protected readonly entity = computed<EntityDef | null>(
    () => this.store.definition()?.entities.find((e) => e.code === this.entityCode) ?? null,
  );

  protected readonly fields = computed(() => this.entity()?.fields ?? []);

  /** The definition is loaded (or failed) and has no entity with the route's code. */
  protected readonly typeMissing = computed(() => {
    const s = this.store.status();
    return (s === 'ready' || s === 'unavailable') && !this.entity();
  });

  /** Read-only unless the instance is open and offers at least one action (11.5, 11.17, 11.18). */
  protected readonly formDisabled = computed(() => {
    const inst = this.instance();
    return !inst || inst.ended || inst.actions.length === 0;
  });

  protected readonly assigneeText = computed(() => {
    const inst = this.instance();
    return displayName(inst?.assigneeName, inst?.assignee);
  });

  protected readonly dueAt = computed(() => this.instance()?.dueAt ?? null);
  protected readonly sla = computed<SlaState>(() =>
    slaState(this.dueAt(), new Date(), WARNING_WINDOW_MS, this.instance()?.ended === true),
  );

  /** Violations for the dialog inputs while a dialog is open. */
  protected readonly dialogViolations = computed<Violation[]>(() => {
    const err = this.actionError();
    if (!err || !this.openAction()) {
      return [];
    }
    return err.violations.filter((v) => DIALOG_FIELDS.includes(v.field));
  });

  /** The action error for the form: violations the dialog does not show. */
  protected readonly formError = computed<ApiError | null>(() => {
    const err = this.actionError();
    if (!err) {
      return null;
    }
    if (!this.openAction()) {
      return err;
    }
    const rest = err.violations.filter((v) => !DIALOG_FIELDS.includes(v.field));
    if (err.violations.length > 0 && rest.length === 0) {
      return null;
    }
    return { ...err, violations: rest };
  });

  ngOnInit(): void {
    if (this.app()) {
      this.store.load(this.app());
    }
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.notFound.set(false);
    this.api
      .getRecord(this.app(), this.entityCode, this.recordId)
      .pipe(
        switchMap((rec) =>
          rec.instanceId
            ? forkJoin({
                record: of(rec),
                instance: this.api.getInstance(rec.instanceId),
                history: this.api.history(rec.instanceId),
              })
            : of({ record: rec, instance: null as InstanceView | null, history: [] as HistoryEntry[] }),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ record, instance, history }) => {
          this.record.set(record);
          this.instance.set(instance);
          this.history.set(history);
          this.loading.set(false);
        },
        error: (err: ApiError) => {
          this.loading.set(false);
          this.handleLoadError(err);
        },
      });
  }

  protected onAction(action: ActionInfo): void {
    if (this.pending()) {
      return;
    }
    if (action.remarks !== 'hidden' || action.attachment !== 'hidden') {
      this.actionError.set(null);
      this.openAction.set(action);
      return;
    }
    if (action.confirm) {
      this.modal.confirm({
        nzTitle: action.label,
        nzContent: action.confirm,
        nzOnOk: () => this.run(action),
      });
      return;
    }
    this.run(action);
  }

  protected closeDialog(): void {
    this.openAction.set(null);
    this.actionError.set(null);
  }

  protected run(action: ActionInfo, extra?: ActionDialogResult): void {
    const inst = this.instance();
    const rec = this.record();
    if (!inst || !rec || this.pending()) {
      return;
    }
    const body = buildActionBody(
      rec.data ?? {},
      this.form()?.values() ?? {},
      inst.fieldRules ?? {},
      extra?.remarks,
      extra?.attachment,
    );
    this.pending.set(true);
    this.actionError.set(null);
    this.api.runAction(inst.instanceId, action.code, body).subscribe({
      next: (view) => {
        this.pending.set(false);
        this.openAction.set(null);
        this.instance.set(view);
        this.reloadRecord();
        this.reloadHistory(view.instanceId);
        this.message.success(`${action.label}: done`);
      },
      error: (err: ApiError) => {
        this.pending.set(false);
        this.onActionError(err, inst.instanceId);
      },
    });
  }

  private onActionError(err: ApiError, instanceId: string): void {
    if (err.status === 422 || err.status === 403) {
      this.actionError.set(err);
      if (err.violations.length === 0) {
        this.message.error(errorText(err));
      }
    } else if (err.status === 409) {
      this.message.error(errorText(err));
      this.openAction.set(null);
      this.api.getInstance(instanceId).subscribe({
        next: (view) => this.instance.set(view),
        error: (e: ApiError) => this.handleLoadError(e),
      });
    } else if (err.status === 404) {
      this.notFound.set(true);
    } else {
      this.message.error(errorText(err));
    }
  }

  private reloadRecord(): void {
    this.api.getRecord(this.app(), this.entityCode, this.recordId).subscribe({
      next: (rec) => this.record.set(rec),
      error: (e: ApiError) => this.handleLoadError(e),
    });
  }

  private reloadHistory(instanceId: string): void {
    this.api.history(instanceId).subscribe({
      next: (h) => this.history.set(h),
      error: (e: ApiError) => this.handleLoadError(e),
    });
  }

  private handleLoadError(err: ApiError): void {
    if (err.status === 404) {
      this.notFound.set(true);
    } else {
      this.loadError.set(err);
    }
  }
}
