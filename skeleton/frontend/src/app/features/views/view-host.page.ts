import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { map } from 'rxjs';
import { DefinitionStore } from '../../core/definition/definition.store';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { DynamicTableComponent } from './dynamic-table.component';
import { KanbanComponent } from './kanban.component';
import { MyTasksComponent } from './my-tasks.component';

/** Route `/app/:app/views/:view`: finds the view in the restricted definition and renders it by type. */
@Component({
  selector: 'sk-view-host-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzSpinModule, EmptyStateComponent, DynamicTableComponent, KanbanComponent, MyTasksComponent],
  template: `
    @if (store.status() === 'ready') {
      @if (resolved(); as r) {
        @switch (r.view.type) {
          @case ('TABLE') {
            <sk-dynamic-table [app]="r.app" [view]="r.view" [entity]="r.entity" />
          }
          @case ('KANBAN') {
            <sk-kanban [app]="r.app" [view]="r.view" [entity]="r.entity" [workflows]="r.workflows" />
          }
          @case ('MY_TASKS') {
            <sk-my-tasks [app]="r.app" [view]="r.view" [entity]="r.entity" />
          }
          @default {
            <sk-empty-state message="View not available" />
          }
        }
      } @else {
        <sk-empty-state message="View not available" />
      }
    } @else if (store.status() === 'loading' || store.status() === 'idle') {
      <nz-spin nzSimple />
    }
  `,
})
export class ViewHostPage {
  protected readonly store = inject(DefinitionStore);
  private readonly route = inject(ActivatedRoute);

  private readonly viewCode = toSignal(this.route.paramMap.pipe(map((p) => p.get('view'))), {
    initialValue: this.route.snapshot.paramMap.get('view'),
  });

  protected readonly resolved = computed(() => {
    const def = this.store.definition();
    const code = this.viewCode();
    if (!def || !code) return null;
    const view = (def.views ?? []).find((v) => v.code === code);
    const entity = view ? def.entities.find((e) => e.code === view.entity) : undefined;
    if (!view || !entity) return null;
    return { app: def.app.code, view, entity, workflows: def.workflows ?? [] };
  });
}
