import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { Router } from '@angular/router';
import { DefinitionStore } from '../../core/definition/definition.store';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';

/** Route `/app/:app`: redirects to the first view, or shows an empty state when there is none. */
@Component({
  selector: 'sk-app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyStateComponent],
  template: `
    @if (store.definition(); as def) {
      @if (def.views.length === 0) {
        <sk-empty-state message="This app has no views yet." />
      }
    }
  `,
})
export class AppHomeComponent {
  protected readonly store = inject(DefinitionStore);
  private readonly router = inject(Router);

  constructor() {
    effect(() => {
      const def = this.store.definition();
      const first = def?.views?.[0];
      if (def && first) {
        void this.router.navigate(['/app', def.app.code, 'views', first.code], { replaceUrl: true });
      }
    });
  }
}
