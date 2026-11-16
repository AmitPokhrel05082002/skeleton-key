import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild, type OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzResultModule } from 'ng-zorro-antd/result';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, FieldDef, FormDef, SubmitResult } from '../../core/api/api.types';
import { SessionService } from '../../core/auth/session.service';
import { DefinitionStore, canSubmitForm } from '../../core/definition/definition.store';
import { publicFormFields } from '../public-form/public-form.page';
import { DynamicFormComponent } from './dynamic-form.component';

/** Form page for signed-in users, opened from the "New" menu entries. */
@Component({
  selector: 'sk-authed-form-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DynamicFormComponent, NzButtonModule, NzResultModule, NzSpinModule, RouterLink],
  template: `
    @if (store.status() === 'idle' || store.status() === 'loading') {
      <nz-spin nzSimple />
    } @else if (!formDef()) {
      <h1 class="sk-authed-title">Form not available</h1>
      <p>This form is not available.</p>
    } @else if (confirmation(); as done) {
      <nz-result nzStatus="success" nzTitle="Submitted" [nzSubTitle]="'Reference number: ' + done.referenceNo">
        <div nz-result-extra>
          <a nz-button nzType="primary" [routerLink]="['/app', app()]">Back to app home</a>
        </div>
      </nz-result>
    } @else {
      <h1 class="sk-authed-title">{{ formTitle() }}</h1>
      <sk-dynamic-form
        [fields]="fields()"
        [sections]="formDef()?.sections"
        [serverError]="serverError()"
        (submitted)="onSubmitted($event)"
      />
      <button
        nz-button
        nzType="primary"
        type="button"
        class="sk-authed-submit"
        [disabled]="pending()"
        [nzLoading]="pending()"
        (click)="form()?.submit()"
      >
        Submit
      </button>
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 720px;
    }
  `,
})
export class AuthedFormPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  protected readonly store = inject(DefinitionStore);

  protected readonly app = signal(
    this.route.parent?.snapshot.paramMap.get('app') ?? this.route.snapshot.paramMap.get('app') ?? '',
  );
  protected readonly formCode = signal(this.route.snapshot.paramMap.get('form') ?? '');

  protected readonly form = viewChild(DynamicFormComponent);
  protected readonly pending = signal(false);
  protected readonly serverError = signal<ApiError | null>(null);
  protected readonly confirmation = signal<SubmitResult | null>(null);

  protected readonly formDef = computed<FormDef | null>(() => {
    const def = this.store.definition();
    const f = def?.forms.find((x) => x.code === this.formCode()) ?? null;
    return f && canSubmitForm(f, this.session.roles()) ? f : null;
  });

  protected readonly fields = computed<FieldDef[]>(() => {
    const def = this.store.definition();
    const f = this.formDef();
    return def && f ? publicFormFields({ app: def.app, forms: def.forms, entities: def.entities }, f) : [];
  });
  protected readonly formTitle = computed(() => this.formDef()?.label || this.formDef()?.code || '');

  ngOnInit(): void {
    if (this.app()) {
      this.store.load(this.app());
    }
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
