import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <main class="sk-error-page">
      <h1>Page not found</h1>
      <p>The page you asked for does not exist.</p>
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
export class NotFoundPage {}
