import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTableModule } from 'ng-zorro-antd/table';
import { toApiError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, EntityDef, RecordSummary, ViewDef } from '../../core/api/api.types';
import { WARNING_WINDOW_MS, slaState, type SlaState } from '../../core/pure/sla';
import { buildListQuery, formatCell } from '../../core/pure/view-helpers';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { ErrorAlertComponent } from '../../shared/error-alert/error-alert.component';
import { SlaBadgeComponent } from '../../shared/sla-badge/sla-badge.component';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';

export const MY_TASKS_PAGE_SIZE = 50;

/** Sort used when the view declares no `defaultSort`: earliest due date first. */
export const MY_TASKS_DEFAULT_SORT = { field: '$dueAt', direction: 'ASC' as const };

/** List of the tasks of the MY_TASKS view. Inputs are independent of routing. */
@Component({
  selector: 'sk-my-tasks',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzButtonModule, NzSpinModule, NzTableModule, EmptyStateComponent, ErrorAlertComponent, SlaBadgeComponent, StatusChipComponent],
  template: `
    @if (error(); as e) {
      <sk-error-alert [error]="e" (retry)="reload()" />
    }
    <nz-spin [nzSpinning]="loading()">
      @if (!loading() && !error() && records().length === 0) {
        <sk-empty-state message="No tasks" />
      } @else {
        <nz-table #t nzSize="middle" [nzData]="records()" [nzFrontPagination]="false" [nzShowPagination]="false">
          <thead>
            <tr>
              <th>Reference</th>
              <th>Title</th>
              <th>Step</th>
              <th>Status</th>
              <th>Due</th>
            </tr>
          </thead>
          <tbody>
            @for (r of t.data; track r.id) {
              <tr class="row" tabindex="0" (click)="open(r)" (keydown.enter)="open(r)">
                <td>{{ r.referenceNo }}</td>
                <td>{{ title(r) }}</td>
                <td>{{ r.stepLabel ?? '' }}</td>
                <td>
                  @if (r.status) {
                    <sk-status-chip [label]="r.status" [color]="r.statusColor" />
                  }
                </td>
                <td>
                  {{ due(r) }}
                  <sk-sla-badge [state]="sla(r)" />
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </nz-spin>
    @if (hasMore()) {
      <div class="more">
        <button nz-button type="button" [disabled]="loading()" (click)="loadMore()">Load more</button>
      </div>
    }
  `,
  styles: `
    .row {
      cursor: pointer;
    }
    .more {
      margin-top: 12px;
      text-align: center;
    }
  `,
})
export class MyTasksComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly app = input.required<string>();
  readonly view = input.required<ViewDef>();
  readonly entity = input<EntityDef | null | undefined>(null);

  protected readonly records = signal<RecordSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly hasMore = computed(() => this.total() > this.records().length);

  private requestId = 0;

  constructor() {
    effect(() => {
      const app = this.app();
      const view = this.view();
      untracked(() => this.restart(app, view));
    });
  }

  protected reload(): void {
    this.restart(this.app(), this.view());
  }

  protected loadMore(): void {
    this.fetch(this.app(), this.view(), this.page() + 1);
  }

  private restart(app: string, view: ViewDef): void {
    this.records.set([]);
    this.total.set(0);
    this.page.set(0);
    this.fetch(app, view, 1);
  }

  private fetch(app: string, view: ViewDef, page: number): void {
    const id = ++this.requestId;
    this.loading.set(true);
    this.error.set(null);
    const query = buildListQuery({
      view: view.code,
      page,
      size: MY_TASKS_PAGE_SIZE,
      defaultSort: view.defaultSort ?? MY_TASKS_DEFAULT_SORT,
      filters: {},
    });
    this.api.listRecords(app, view.entity, query).subscribe({
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

  protected title(r: RecordSummary): string {
    const code = this.entity()?.titleField;
    if (!code) return '';
    const field = this.entity()?.fields.find((f) => f.code === code);
    return formatCell(field, r.data?.[code]);
  }

  protected due(r: RecordSummary): string {
    return r.dueAt ? formatCell({ code: 'x', label: 'x', type: 'DATETIME' }, r.dueAt) : '';
  }

  protected sla(r: RecordSummary): SlaState {
    return slaState(r.dueAt, new Date(), WARNING_WINDOW_MS, r.ended === true);
  }

  protected open(r: RecordSummary): void {
    void this.router.navigate(['/app', this.app(), 'records', this.view().entity, r.id]);
  }
}
