import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-authorised-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <main class="sk-error-page">
      <h1>Not authorised</h1>
      <p>You do not have permission to open this page.</p>
      <a routerLink="/">Go to the start page</a>
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
export class NotAuthorisedPage {}
