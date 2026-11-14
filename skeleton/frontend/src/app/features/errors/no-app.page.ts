import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzInputModule } from 'ng-zorro-antd/input';

@Component({
  selector: 'app-no-app-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, NzInputModule, NzButtonModule],
  template: `
    <main class="sk-error-page">
      <h1>No app configured</h1>
      <p>No app is configured for the host "{{ host }}". Enter an app code to continue.</p>
      <form (submit)="go($event)">
        <label for="sk-app-code">App code</label>
        <input
          id="sk-app-code"
          nz-input
          name="appCode"
          autocomplete="off"
          [ngModel]="code()"
          (ngModelChange)="code.set($event)"
        />
        <button nz-button nzType="primary" type="submit" [disabled]="!code().trim()">Open app</button>
      </form>
    </main>
  `,
  styles: `
    .sk-error-page {
      max-width: 40rem;
      margin: 4rem auto;
      padding: 0 1rem;
    }
    label {
      display: block;
      margin-bottom: 0.25rem;
    }
    button {
      margin-top: 0.75rem;
    }
  `,
})
export class NoAppPage {
  private readonly router = inject(Router);
  protected readonly host = window.location.hostname;
  protected readonly code = signal('');

  protected go(event: Event): void {
    event.preventDefault();
    const value = this.code().trim();
    if (value) {
      void this.router.navigate(['/app', value]);
    }
  }
}
