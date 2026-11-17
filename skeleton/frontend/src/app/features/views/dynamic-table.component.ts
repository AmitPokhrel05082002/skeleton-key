import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, map, of, switchMap, tap } from 'rxjs';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTableModule } from 'ng-zorro-antd/table';
import { toApiError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, EntityDef, Page, RecordSummary, ViewDef } from '../../core/api/api.types';
import { displayName } from '../../core/pure/display-name';
import { WARNING_WINDOW_MS, slaState, type SlaState } from '../../core/pure/sla';
import {
  buildListQuery,
  formatCell,
  resolveColumns,
  withFilters,
  withSort,
  type ColumnSpec,
  type ListState,
} from '../../core/pure/view-helpers';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { ErrorAlertComponent } from '../../shared/error-alert/error-alert.component';
import { SlaBadgeComponent } from '../../shared/sla-badge/sla-badge.component';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';

export const TABLE_DEFAULT_PAGE_SIZE = 20;
export const TABLE_PAGE_SIZES: readonly number[] = [10, 20, 50, 100];

interface FilterSpec {
  key: string;
  label: string;
  kind: 'select' | 'boolean' | 'date' | 'text';
  options: { value: string; label: string }[];
}

type Outcome = { page: Page<RecordSummary> } | { error: ApiError };

const BOOLEAN_OPTIONS = [
  { value: 'true', label: 'Yes' },
  { value: 'false', label: 'No' },
];

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** Renders a TABLE view: paging, sorting and filters against `listRecords`. Independent of routing. */
@Component({
  selector: 'sk-dynamic-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    NzDatePickerModule,
    NzInputModule,
    NzSelectModule,
    NzSpinModule,
    NzTableModule,
    EmptyStateComponent,
    ErrorAlertComponent,
    SlaBadgeComponent,
    StatusChipComponent,
  ],
  template: `
    @if (error(); as e) {
      <sk-error-alert [error]="e" [unmatched]="violationMessages()" (retry)="reload()" />
    }
    @if (filterSpecs().length > 0) {
      <div class="filters">
        @for (f of filterSpecs(); track f.key) {
          <div class="filter" role="group" [attr.aria-label]="f.label">
            <span class="filter-label">{{ f.label }}</span>
            @switch (f.kind) {
              @case ('select') {
                <nz-select
                  class="filter-control"
                  nzAllowClear
                  [ngModel]="filterValue(f.key)"
                  (ngModelChange)="setFilter(f.key, $event)"
                >
                  @for (o of f.options; track o.value) {
                    <nz-option [nzValue]="o.value" [nzLabel]="o.label" />
                  }
                </nz-select>
              }
              @case ('boolean') {
                <nz-select
                  class="filter-control"
                  nzAllowClear
                  [ngModel]="filterValue(f.key)"
                  (ngModelChange)="setFilter(f.key, $event)"
                >
                  @for (o of f.options; track o.value) {
                    <nz-option [nzValue]="o.value" [nzLabel]="o.label" />
                  }
                </nz-select>
              }
              @case ('date') {
                <nz-date-picker
                  class="filter-control"
                  [ngModel]="dateValue(f.key)"
                  (ngModelChange)="setFilter(f.key, formatDate($event))"
                />
              }
              @default {
                <input
                  nz-input
                  class="filter-control filter-text"
                  [value]="filterValue(f.key) ?? ''"
                  (keyup.enter)="setFilter(f.key, $any($event.target).value)"
                  (blur)="setFilter(f.key, $any($event.target).value)"
                />
              }
            }
          </div>
        }
      </div>
    }
    <nz-spin [nzSpinning]="loading()">
      @if (loaded() && items().length === 0 && !error()) {
        <sk-empty-state message="No records found" />
      } @else {
        <nz-table
          #t
          nzSize="middle"
          [nzData]="items()"
          [nzFrontPagination]="false"
          [nzScroll]="scroll"
          [nzTotal]="total()"
          [nzPageIndex]="state().page"
          [nzPageSize]="state().size"
          [nzPageSizeOptions]="pageSizes"
          [nzShowSizeChanger]="true"
          [nzShowTotal]="totalTpl"
          (nzPageIndexChange)="onPage($event)"
          (nzPageSizeChange)="onSize($event)"
        >
          <thead>
            <tr>
              @for (c of columns(); track c.key) {
                <th
                  [nzColumnKey]="c.key"
                  [nzSortFn]="true"
                  [nzSortOrder]="sortOrder(c.key)"
                  (nzSortOrderChange)="onSort(c.key, $event)"
                >
                  {{ c.label }}
                </th>
              }
            </tr>
          </thead>
          <tbody>
            @for (r of t.data; track r.id) {
              <tr class="row" tabindex="0" (click)="open(r)" (keydown.enter)="open(r)">
                @for (c of columns(); track c.key) {
                  <td>
                    @switch (c.key) {
                      @case ('$status') {
                        @if (r.status) {
                          <sk-status-chip [label]="r.status" [color]="r.statusColor" />
                        }
                      }
                      @case ('$step') {
                        {{ r.stepLabel ?? '' }}
                      }
                      @case ('$assignee') {
                        {{ assignee(r) }}
                      }
                      @case ('$dueAt') {
                        {{ dateTime(r.dueAt) }}
                        <sk-sla-badge [state]="sla(r)" />
                      }
                      @default {
                        {{ cell(c, r) }}
                      }
                    }
                  </td>
                }
              </tr>
            }
          </tbody>
        </nz-table>
        <ng-template #totalTpl let-total>{{ total }} records</ng-template>
      }
    </nz-spin>
  `,
  styles: `
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      margin-bottom: 12px;
    }
    .filter {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .filter-label {
      font-size: 12px;
      opacity: 0.8;
    }
    .filter-control {
      min-width: 160px;
    }
    .row {
      cursor: pointer;
    }
  `,
})
export class DynamicTableComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly app = input.required<string>();
  readonly view = input.required<ViewDef>();
  readonly entity = input.required<EntityDef>();

  protected readonly pageSizes: number[] = [...TABLE_PAGE_SIZES];
  /** Wide tables scroll inside their own container instead of widening the page. */
  protected readonly scroll = { x: 'max-content' };

  /** Page, size, sort and filters. Resets whenever the app or view input changes. */
  protected readonly state = linkedSignal<{ app: string; view: ViewDef }, ListState>({
    source: () => ({ app: this.app(), view: this.view() }),
    computation: ({ view }) => ({
      view: view.code,
      page: 1,
      size: TABLE_DEFAULT_PAGE_SIZE,
      defaultSort: view.defaultSort,
      filters: {},
    }),
  });

  private readonly reloadTick = signal(0);
  private readonly result = signal<Page<RecordSummary> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected readonly items = computed(() => this.result()?.items ?? []);
  protected readonly total = computed(() => this.result()?.total ?? 0);
  protected readonly loaded = computed(() => this.result() !== null);
  protected readonly columns = computed(() => resolveColumns(this.view(), this.entity()));
  protected readonly violationMessages = computed(() =>
    (this.error()?.violations ?? []).map((v) => v.message ?? `${v.field}: ${v.rule}`),
  );

  protected readonly filterSpecs = computed<FilterSpec[]>(() =>
    (this.view().filters ?? []).map((key) => {
      if (key.startsWith('$')) {
        return { key, label: resolveColumns({ ...this.view(), columns: [key] }, this.entity())[0].label, kind: 'text', options: [] };
      }
      const field = this.entity().fields.find((f) => f.code === key);
      const label = field?.label ?? key;
      switch (field?.type) {
        case 'SELECT':
          return { key, label, kind: 'select', options: field.options ?? [] };
        case 'BOOLEAN':
          return { key, label, kind: 'boolean', options: BOOLEAN_OPTIONS };
        case 'DATE':
          return { key, label, kind: 'date', options: [] };
        default:
          return { key, label, kind: 'text', options: [] };
      }
    }),
  );

  private readonly request = computed(() => ({
    app: this.app(),
    entity: this.view().entity,
    query: buildListQuery(this.state()),
    tick: this.reloadTick(),
  }));

  constructor() {
    toObservable(this.request)
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set(null);
        }),
        switchMap((req) =>
          this.api.listRecords(req.app, req.entity, req.query).pipe(
            map((page): Outcome => ({ page })),
            catchError((e: unknown) => of<Outcome>({ error: toApiError(e) })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((outcome) => {
        // On error the previous rows stay in `result`.
        if ('page' in outcome) {
          this.result.set(outcome.page);
        } else {
          this.error.set(outcome.error);
        }
        this.loading.set(false);
      });
  }

  protected reload(): void {
    this.reloadTick.update((n) => n + 1);
  }

  protected onPage(page: number): void {
    if (page !== this.state().page) this.state.update((s) => ({ ...s, page }));
  }

  protected onSize(size: number): void {
    if (size !== this.state().size) this.state.update((s) => ({ ...s, size, page: 1 }));
  }

  protected sortOrder(key: string): 'ascend' | 'descend' | null {
    const s = this.state();
    const active = s.sort ?? s.defaultSort;
    if (!active || active.field !== key) return null;
    return active.direction === 'ASC' ? 'ascend' : 'descend';
  }

  protected onSort(key: string, order: string | null): void {
    const sort = order === 'ascend' || order === 'descend' ? { field: key, direction: order === 'ascend' ? ('ASC' as const) : ('DESC' as const) } : undefined;
    const current = this.state().sort;
    if (current?.field === sort?.field && current?.direction === sort?.direction) return;
    this.state.update((s) => withSort(s, sort));
  }

  protected filterValue(key: string): string | null {
    return this.state().filters[key] ?? null;
  }

  protected setFilter(key: string, value: string | null | undefined): void {
    const next = (value ?? '').trim();
    if (next === (this.state().filters[key] ?? '')) return;
    this.state.update((s) => withFilters(s, { ...s.filters, [key]: next }));
  }

  protected dateValue(key: string): Date | null {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(this.state().filters[key] ?? '');
    return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
  }

  protected formatDate(d: Date | null): string {
    return d ? `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` : '';
  }

  protected cell(c: ColumnSpec, r: RecordSummary): string {
    switch (c.key) {
      case '$reference':
        return r.referenceNo;
      case '$createdAt':
        return this.dateTime(r.createdAt);
      case '$updatedAt':
        return this.dateTime(r.updatedAt);
      case '$createdBy':
        return r.createdBy ?? '';
      default:
        return formatCell(c.field, r.data?.[c.key]);
    }
  }

  protected dateTime(value: string | null | undefined): string {
    return value ? formatCell({ code: 'x', label: 'x', type: 'DATETIME' }, value) : '';
  }

  protected assignee(r: RecordSummary): string {
    return displayName(r.assigneeName, r.assignee);
  }

  protected sla(r: RecordSummary): SlaState {
    return slaState(r.dueAt, new Date(), WARNING_WINDOW_MS, r.ended === true);
  }

  protected open(r: RecordSummary): void {
    void this.router.navigate(['/app', this.app(), 'records', this.view().entity, r.id]);
  }
}
