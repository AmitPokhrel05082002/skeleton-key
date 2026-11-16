import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal, viewChild, type OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzResultModule } from 'ng-zorro-antd/result';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, FieldDef, FormDef, PublicSubset, SubmitResult } from '../../core/api/api.types';
import { safeLogoUrl } from '../../core/pure/safe-url';
import { ThemeService } from '../../core/theme/theme.service';
import { DynamicFormComponent } from '../form/dynamic-form.component';

type LoadState = 'loading' | 'ready' | 'unavailable';

/** Builds the FieldDef list of a form from the fields it lists, in section order, from the Public_Subset only. */
export function publicFormFields(subset: PublicSubset, form: FormDef): FieldDef[] {
  const pool = form.fields ?? subset.entities?.find((e) => e.code === form.entity)?.fields ?? [];
  const byCode = new Map(pool.map((f) => [f.code, f]));
  const seen = new Set<string>();
  const result: FieldDef[] = [];
  for (const section of form.sections) {
    for (const code of section.fields) {
      const field = byCode.get(code);
      if (field && !seen.has(code)) {
        seen.add(code);
        result.push(field);
      }
    }
  }
  return result;
}

@Component({
  selector: 'sk-public-form-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DynamicFormComponent, NzButtonModule, NzResultModule, NzSpinModule],
  template: `
    <main class="sk-public">
      @switch (state()) {
        @case ('loading') {
          <nz-spin nzSimple />
        }
        @case ('unavailable') {
          <h1 class="sk-public-title">Form not available</h1>
          <p>This form is not available.</p>
        }
        @default {
          <header class="sk-public-header">
            @if (logo(); as src) {
              <img class="sk-public-logo" [src]="src" alt="" />
            }
            <span class="sk-public-app">{{ appName() }}</span>
          </header>
          @if (confirmation(); as done) {
            <nz-result nzStatus="success" nzTitle="Submitted" [nzSubTitle]="'Reference number: ' + done.referenceNo" />
          } @else {
            <h1 class="sk-public-title">{{ formTitle() }}</h1>
            <sk-dynamic-form
              [fields]="fields()"
              [sections]="sections()"
              [serverError]="serverError()"
              (submitted)="onSubmitted($event)"
            />
            <button
              nz-button
              nzType="primary"
              type="button"
              class="sk-public-submit"
              [disabled]="pending()"
              [nzLoading]="pending()"
              (click)="form()?.submit()"
            >
              Submit
            </button>
          }
        }
      }
    </main>
  `,
  styles: `
    .sk-public {
      box-sizing: border-box;
      margin: 0 auto;
      max-width: 720px;
      padding: 16px;
      width: 100%;
    }
    .sk-public-header {
      align-items: center;
      display: flex;
      gap: 12px;
      margin-bottom: 16px;
    }
    .sk-public-logo {
      max-height: 40px;
      max-width: 160px;
    }
    .sk-public-app {
      font-weight: 600;
    }
    @media (max-width: 575.98px) {
      .sk-public {
        padding: 12px;
      }
      :host ::ng-deep .ant-input,
      :host ::ng-deep .ant-input-number,
      :host ::ng-deep .ant-input-number-input,
      :host ::ng-deep .ant-select-selector,
      :host ::ng-deep .ant-picker,
      :host ::ng-deep .ant-btn {
        min-height: 44px;
      }
      .sk-public-submit {
        width: 100%;
      }
    }
  `,
})
export class PublicFormPage implements OnInit {
  private readonly params = inject(ActivatedRoute).snapshot.paramMap;
  protected readonly app = signal(this.params.get('app') ?? '');
  protected readonly formCode = signal(this.params.get('form') ?? '');

  private readonly api = inject(ApiService);
  private readonly theme = inject(ThemeService);

  protected readonly form = viewChild(DynamicFormComponent);
  protected readonly state = signal<LoadState>('loading');
  protected readonly subset = signal<PublicSubset | null>(null);
  protected readonly pending = signal(false);
  protected readonly serverError = signal<ApiError | null>(null);
  protected readonly confirmation = signal<SubmitResult | null>(null);

  private readonly formDef = computed<FormDef | null>(() => {
    const s = this.subset();
    return s?.forms.find((f) => f.code === this.formCode()) ?? null;
  });

  protected readonly fields = computed<FieldDef[]>(() => {
    const s = this.subset();
    const f = this.formDef();
    return s && f ? publicFormFields(s, f) : [];
  });
  protected readonly sections = computed(() => this.formDef()?.sections);
  protected readonly formTitle = computed(() => this.formDef()?.label ?? this.formDef()?.code ?? '');
  protected readonly appName = computed(() => this.subset()?.app.name ?? '');
  protected readonly logo = computed(() => safeLogoUrl(this.subset()?.app.branding?.logo));

  constructor() {
    inject(DestroyRef).onDestroy(() => this.theme.applyBranding(null));
  }

  ngOnInit(): void {
    this.api.publicApp(this.app()).subscribe({
      next: (subset) => {
        if (!subset.forms.some((f) => f.code === this.formCode())) {
          this.state.set('unavailable');
          return;
        }
        this.subset.set(subset);
        void this.theme.apply(subset.app.branding?.theme);
        this.theme.applyBranding(subset.app.branding?.primaryColor);
        this.state.set('ready');
      },
      error: () => this.state.set('unavailable'),
    });
  }

  protected onSubmitted(values: Record<string, unknown>): void {
    if (this.pending()) {
      return;
    }
    this.pending.set(true);
    this.serverError.set(null);
    this.api.submitForm(this.app(), this.formCode(), values).subscribe({
      next: (result) => {
        this.pending.set(false);
        this.confirmation.set(result);
      },
      error: (err: ApiError) => {
        this.pending.set(false);
        this.serverError.set(err);
      },
    });
  }
}
