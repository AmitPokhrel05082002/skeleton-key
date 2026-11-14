import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RuntimeConfigService } from '../../core/config/runtime-config.service';

@Component({
  selector: 'app-config-error-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="sk-error-page" role="alert">
      <h1>Configuration error</h1>
      <p>The runtime configuration (assets/config.json) could not be loaded or is invalid.</p>
      @if (config.configError(); as detail) {
        <p>{{ detail }}</p>
      }
    </main>
  `,
  styles: `
    .sk-error-page {
      max-width: 40rem;
      margin: 4rem auto;
      padding: 0 1rem;
    }
  `,
})
export class ConfigErrorPage {
  protected readonly config = inject(RuntimeConfigService);
}
