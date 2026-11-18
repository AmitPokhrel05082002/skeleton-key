import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal, type OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTimelineModule } from 'ng-zorro-antd/timeline';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, PublicSubset, TrackResponse } from '../../core/api/api.types';
import { safeLogoUrl } from '../../core/pure/safe-url';
import { ThemeService } from '../../core/theme/theme.service';
import { statusChipColor, toTimelineItems, validateTrackForm } from './track-model';

export const TRACK_NOT_FOUND =
  'We could not find a request with that reference and email. Check both and try again.';
export const TRACK_RATE_LIMITED = 'Too many attempts. Please wait a minute and try again.';
export const TRACK_GENERIC_ERROR = 'Something went wrong. Please try again later.';

/**
 * Public status lookup. The email and the result live only in component signals:
 * nothing is written to storage or cookies and the email is never read from or put in the URL.
 */
@Component({
  selector: 'sk-track-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, NzAlertModule, NzButtonModule, NzCardModule, NzInputModule, NzTagModule, NzTimelineModule],
  template: `
    <main class="sk-track">
      <header class="sk-track-header">
        @if (logo(); as src) {
          <img class="sk-track-logo" [src]="src" alt="" />
        }
        <span class="sk-track-app">{{ appName() }}</span>
      </header>
      <h1 class="sk-track-title">Track your request</h1>

      <form class="sk-track-form" (ngSubmit)="submit()" novalidate autocomplete="off">
        <label for="sk-track-reference">Reference number</label>
        <input
          id="sk-track-reference"
          nz-input
          name="reference"
          autocomplete="off"
          [ngModel]="reference()"
          (ngModelChange)="reference.set($event)"
        />
        @if (referenceError(); as e) {
          <div class="sk-field-error" role="alert">{{ e }}</div>
        }
        <label for="sk-track-email">Email address</label>
        <input
          id="sk-track-email"
          nz-input
          type="email"
          name="email"
          autocomplete="off"
          [ngModel]="email()"
          (ngModelChange)="email.set($event)"
        />
        @if (emailError(); as e) {
          <div class="sk-field-error" role="alert">{{ e }}</div>
        }
        <button nz-button nzType="primary" type="submit" class="sk-track-submit" [nzLoading]="pending()" [disabled]="!canSubmit()">
          Check status
        </button>
        @if (waitSeconds() > 0) {
          <span class="sk-track-wait">Try again in {{ waitSeconds() }} s</span>
        }
      </form>

      @if (message(); as m) {
        <nz-alert class="sk-track-message" nzType="error" [nzMessage]="m" role="alert" />
      }
      @for (v of serverViolations(); track $index) {
        <div class="sk-field-error sk-track-violation" role="alert">{{ v }}</div>
      }

      @if (result(); as r) {
        <nz-card class="sk-track-result" [nzTitle]="r.reference">
          @if (r.title) {
            <p class="sk-track-result-title">{{ r.title }}</p>
          }
          <p>
            Status:
            <nz-tag class="sk-track-status" [nzColor]="chipColor()">{{ r.status.label }}</nz-tag>
          </p>
          <p class="sk-track-dates">
            Created {{ r.createdAt | date: 'medium' }}. Last updated {{ r.updatedAt | date: 'medium' }}.
          </p>
          <nz-timeline>
            @for (item of timeline(); track $index) {
              <nz-timeline-item>
                <span class="sk-track-status-name">{{ item.status }}</span>
                <span class="sk-track-at"> {{ item.at | date: 'medium' }}</span>
              </nz-timeline-item>
            }
          </nz-timeline>
        </nz-card>
      }
    </main>
  `,
  styles: `
    .sk-track {
      box-sizing: border-box;
      margin: 0 auto;
      max-width: 640px;
      padding: 16px;
      width: 100%;
    }
    .sk-track-header {
      align-items: center;
      display: flex;
      gap: 12px;
      margin-bottom: 16px;
    }
    .sk-track-logo {
      max-height: 40px;
      max-width: 160px;
    }
    .sk-track-app {
      font-weight: 600;
    }
    .sk-track-form label {
      display: block;
      margin: 12px 0 4px;
    }
    .sk-track-submit {
      margin-top: 16px;
    }
    .sk-track-wait {
      margin-left: 12px;
    }
    .sk-track-message,
    .sk-track-result {
      margin-top: 16px;
    }
    .sk-field-error {
      color: #ff4d4f;
      margin-top: 4px;
    }
  `,
})
export class TrackPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ApiService);
  private readonly theme = inject(ThemeService);

  protected readonly app = signal(this.route.snapshot.paramMap.get('app') ?? '');
  protected readonly reference = signal(this.route.snapshot.queryParamMap.get('reference') ?? '');
  protected readonly email = signal('');

  protected readonly subset = signal<PublicSubset | null>(null);
  protected readonly pending = signal(false);
  protected readonly touched = signal(false);
  protected readonly waitSeconds = signal(0);
  protected readonly message = signal<string | null>(null);
  protected readonly serverViolations = signal<string[]>([]);
  protected readonly result = signal<TrackResponse | null>(null);

  protected readonly appName = computed(() => this.subset()?.app.name ?? '');
  protected readonly logo = computed(() => safeLogoUrl(this.subset()?.app.branding?.logo));
  private readonly validation = computed(() => validateTrackForm(this.reference(), this.email()));
  protected readonly referenceError = computed(() => (this.touched() ? (this.validation().errors.reference ?? null) : null));
  protected readonly emailError = computed(() => (this.touched() ? (this.validation().errors.email ?? null) : null));
  protected readonly canSubmit = computed(() => this.validation().valid && !this.pending() && this.waitSeconds() === 0);
  protected readonly chipColor = computed(() => statusChipColor(this.result()?.status));
  protected readonly timeline = computed(() => {
    const r = this.result();
    return r ? toTimelineItems(r) : [];
  });

  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.stopTimer();
      this.theme.applyBranding(null);
    });
  }

  ngOnInit(): void {
    // Branding only; a failure leaves the default look.
    this.api.publicApp(this.app()).subscribe({
      next: (subset) => {
        this.subset.set(subset);
        void this.theme.apply(subset.app.branding?.theme);
        this.theme.applyBranding(subset.app.branding?.primaryColor);
      },
      error: () => undefined,
    });
  }

  protected submit(): void {
    this.touched.set(true);
    if (!this.canSubmit()) {
      return;
    }
    this.pending.set(true);
    this.message.set(null);
    this.serverViolations.set([]);
    this.result.set(null);
    this.api.track(this.app(), { reference: this.reference().trim(), email: this.email().trim() }).subscribe({
      next: (res) => {
        this.pending.set(false);
        this.result.set(res);
      },
      error: (err: ApiError) => {
        this.pending.set(false);
        this.onError(err);
      },
    });
  }

  private onError(err: ApiError): void {
    if (err.status === 404) {
      this.message.set(TRACK_NOT_FOUND);
    } else if (err.status === 429) {
      this.message.set(TRACK_RATE_LIMITED);
      this.startWait(err.retryAfterSeconds ?? 60);
    } else if (err.status === 422 && err.violations.length > 0) {
      this.serverViolations.set(err.violations.map((v) => v.message || `${v.field}: ${v.rule}`));
    } else {
      this.message.set(TRACK_GENERIC_ERROR);
    }
  }

  private startWait(seconds: number): void {
    this.stopTimer();
    this.waitSeconds.set(Math.max(1, Math.ceil(seconds)));
    this.timer = setInterval(() => {
      this.waitSeconds.update((s) => Math.max(0, s - 1));
      if (this.waitSeconds() === 0) {
        this.stopTimer();
      }
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
