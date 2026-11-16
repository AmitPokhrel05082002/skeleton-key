import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, EntityDef, RecordSummary, ViewDef, WorkflowPresentation } from '../../core/api/api.types';
import { onColor } from '../../core/pure/contrast';
import { displayName } from '../../core/pure/display-name';
import { formatCell, groupByStatus } from '../../core/pure/view-helpers';
import { WARNING_WINDOW_MS, slaState, type SlaState } from '../../core/pure/sla';
import { toApiError } from '../../core/api/api-error';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { ErrorAlertComponent } from '../../shared/error-alert/error-alert.component';
import { SlaBadgeComponent } from '../../shared/sla-badge/sla-badge.component';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';

export const KANBAN_PAGE_SIZE = 50;

/**
 * Board of cards grouped by workflow status. Inputs are independent of routing.
 * Cards are read-only: there is no drag behaviour.
 */
@Component({
  selector: 'sk-kanban',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzButtonModule, NzSpinModule, EmptyStateComponent, ErrorAlertComponent, SlaBadgeComponent, StatusChipComponent],
  template: `
    @if (error(); as e) {
      <sk-error-alert [error]="e" (retry)="reload()" />
    }
    <nz-spin [nzSpinning]="loading()">
      @if (!loading() && !error() && records().length === 0 && columns().length === 0) {
        <sk-empty-state message="Nothing to show" />
      } @else {
        <div class="board">
          @for (col of columns(); track col.label) {
            <section class="column" [attr.aria-label]="col.label">
              <header
                class="sk-kanban-header"
                [style.background-color]="headerBg(col.status?.color)"
                [style.color]="headerFg(col.status?.color)"
              >
                {{ col.label }} ({{ col.records.length }})
              </header>
              <div class="cards">
                @for (r of col.records; track r.id) {
                  <div
                    class="card"
                    role="link"
                    tabindex="0"
                    (click)="open(r)"
                    (keydown.enter)="open(r)"
                  >
                    <div class="ref">{{ r.referenceNo }}</div>
                    @if (title(r); as t) {
                      <div class="title">{{ t }}</div>
                    }
                    @if (r.assignee || r.assigneeName) {
                      <div class="assignee">{{ name(r) }}</div>
                    }
                    <div class="meta">
                      @if (r.status) {
                        <sk-status-chip [label]="r.status" [color]="r.statusColor" />
                      }
                      <sk-sla-badge [state]="sla(r)" />
                    </div>
                  </div>
                }
              </div>
            </section>
          }
        </div>
      }
    </nz-spin>
    @if (hasMore()) {
      <div class="more">
        <button nz-button type="button" [disabled]="loading()" (click)="loadMore()">Load more</button>
      </div>
    }
  `,
  styles: `
    .board {
      display: flex;
      gap: 12px;
      align-items: flex-start;
      overflow-x: auto;
    }
    .column {
      flex: 0 0 260px;
      min-width: 260px;
    }
    .sk-kanban-header {
      padding: 8px 12px;
      border-radius: 6px 6px 0 0;
      font-weight: 600;
    }
    .cards {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 8px 0;
    }
    .card {
      padding: 8px 12px;
      border: 1px solid rgba(128, 128, 128, 0.4);
      border-radius: 6px;
      cursor: pointer;
    }
    .ref {
      font-size: 12px;
      opacity: 0.8;
    }
    .title {
      font-weight: 600;
    }
    .meta {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      margin-top: 4px;
    }
    .more {
      margin-top: 12px;
      text-align: center;
    }
  `,
})
export class KanbanComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly view = input.required<ViewDef>();
  readonly entity = input<EntityDef | null | undefined>(null);
  readonly workflows = input<readonly WorkflowPresentation[]>([]);
  readonly app = input.required<string>();

  protected readonly records = signal<RecordSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  private requestId = 0;

  protected readonly statuses = computed(() => {
    const entityCode = this.view().entity;
    return this.workflows().find((w) => w.entity === entityCode)?.statuses ?? [];
  });
  protected readonly columns = computed(() => groupByStatus(this.records(), this.statuses()));
  protected readonly hasMore = computed(() => this.total() > this.records().length);

  constructor() {
    effect(() => {
      const view = this.view();
      const app = this.app();
      untracked(() => {
        this.records.set([]);
        this.total.set(0);
        this.page.set(0);
        this.fetch(app, view, 1);
      });
    });
  }

  protected reload(): void {
    this.records.set([]);
    this.total.set(0);
    this.page.set(0);
    this.fetch(this.app(), this.view(), 1);
  }

  protected loadMore(): void {
    this.fetch(this.app(), this.view(), this.page() + 1);
  }

  private fetch(app: string, view: ViewDef, page: number): void {
    const id = ++this.requestId;
    this.loading.set(true);
    this.error.set(null);
    this.api.listRecords(app, view.entity, { page, size: KANBAN_PAGE_SIZE, view: view.code, filters: {} }).subscribe({
      next: (result) => {
        if (id !== this.requestId) return;
        this.records.update((cur) => (page === 1 ? [...result.items] : [...cur, ...result.items]));
        this.total.set(result.total);
        this.page.set(page);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        if (id !== this.requestId) return;
        this.error.set(toApiError(e));
        this.loading.set(false);
      },
    });
  }

  protected headerBg(color: string | undefined): string | null {
    return this.validColor(color) ? (color ?? null) : null;
  }

  protected headerFg(color: string | undefined): string | null {
    return this.validColor(color) ? onColor(color as string) : null;
  }

  private validColor(color: string | undefined): boolean {
    if (!color) return false;
    try {
      return !!onColor(color);
    } catch {
      return false;
    }
  }

  protected title(r: RecordSummary): string {
    const code = this.entity()?.titleField;
    if (!code) return '';
    const field = this.entity()?.fields.find((f) => f.code === code);
    return formatCell(field, r.data?.[code]);
  }

  protected name(r: RecordSummary): string {
    return displayName(r.assigneeName, r.assignee);
  }

  protected sla(r: RecordSummary): SlaState {
    return slaState(r.dueAt, new Date(), WARNING_WINDOW_MS, r.ended === true);
  }

  protected open(r: RecordSummary): void {
    void this.router.navigate(['/app', this.app(), 'records', this.view().entity, r.id]);
  }
}
