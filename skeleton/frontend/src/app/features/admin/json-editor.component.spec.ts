import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { NzMessageService } from 'ng-zorro-antd/message';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import { ApiError, VersionRef } from '../../core/api/api.types';
import { adminChildren } from './admin.routes';
import { JsonEditorComponent } from './json-editor.component';
import { formatJson, minimalDefinitionTemplate } from './json-editor-helpers';
import { UnsavedChangesPrompt, unsavedChangesGuard } from './unsaved-changes.guard';

const DRAFT: VersionRef = { id: 'id-d', version: 2, status: 'DRAFT', contentHash: 'h2', app: 'NEW_APP' };
const PUBLISHED: VersionRef = { id: 'id-p', version: 1, status: 'PUBLISHED', contentHash: 'h1', app: 'NEW_APP' };

function apiError(over: Partial<ApiError>): ApiError {
  return { status: 500, code: 'X', message: 'boom', violations: [], ...over };
}

describe('JsonEditorComponent', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let navigate: ReturnType<typeof vi.fn>;
  let message: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    vi.useFakeTimers();
    api = {
      validateDefinition: vi.fn(() => of([])),
      createDefinition: vi.fn(() => of(DRAFT)),
      updateDefinition: vi.fn(() => of(DRAFT)),
      editPublished: vi.fn(() => of(DRAFT)),
      publish: vi.fn(() => of({ ...DRAFT, status: 'PUBLISHED' })),
      getDefinition: vi.fn(() => of(JSON.stringify(minimalDefinitionTemplate()))),
      definitionInfo: vi.fn(() => of(DRAFT)),
    };
    message = { success: vi.fn(), error: vi.fn() };
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function create(id: string | null): ComponentFixture<JsonEditorComponent> {
    const paramMap = convertToParamMap(id === null ? {} : { id });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: NzMessageService, useValue: message },
        { provide: ActivatedRoute, useValue: { paramMap: of(paramMap), snapshot: { paramMap } } },
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true) as never;
    const fixture = TestBed.createComponent(JsonEditorComponent);
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<JsonEditorComponent>): HTMLElement => f.nativeElement as HTMLElement;
  const area = (f: ComponentFixture<JsonEditorComponent>): HTMLTextAreaElement =>
    el(f).querySelector('textarea') as HTMLTextAreaElement;
  const btn = (f: ComponentFixture<JsonEditorComponent>, cls: string): HTMLButtonElement =>
    el(f).querySelector(`button.${cls}`) as HTMLButtonElement;

  function type(f: ComponentFixture<JsonEditorComponent>, text: string): void {
    area(f).value = text;
    area(f).dispatchEvent(new Event('input'));
    f.detectChanges();
  }

  it('opens /new with the formatted template, unsaved and not publishable', () => {
    const f = create(null);
    expect(area(f).value).toBe(formatJson(minimalDefinitionTemplate()));
    expect(btn(f, 'sk-publish').disabled).toBe(true);
    expect(btn(f, 'sk-save').disabled).toBe(false);
  });

  it('disables Save and shows the parse error for invalid JSON', () => {
    const f = create(null);
    type(f, '{ "a": ');
    expect(btn(f, 'sk-save').disabled).toBe(true);
    expect(el(f).querySelector('.sk-parse-error')).not.toBeNull();
  });

  it('validates once, 500 ms after the last change', async () => {
    const f = create(null);
    api['validateDefinition'].mockClear();
    type(f, '{"a":1}');
    await vi.advanceTimersByTimeAsync(300);
    type(f, '{"a":2}');
    await vi.advanceTimersByTimeAsync(499);
    expect(api['validateDefinition']).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(api['validateDefinition']).toHaveBeenCalledTimes(1);
    expect(api['validateDefinition']).toHaveBeenCalledWith('{"a":2}');
  });

  it('lists violations and moves the caret when one is clicked (pointer and code path)', async () => {
    const f = create(null);
    const text = '{\n  "roles": [{"code": "R"}],\n  "workflows": [{"code": "W", "steps": [{"code": "S"}]}]\n}';
    api['validateDefinition'].mockReturnValue(
      of([
        { field: '/roles/0', rule: 'ROLE', message: 'bad role' },
        { field: 'workflows[W].steps[S]', rule: 'STEP', message: 'bad step' },
      ]),
    );
    type(f, text);
    await vi.advanceTimersByTimeAsync(500);
    f.detectChanges();
    const items = el(f).querySelectorAll<HTMLButtonElement>('button.sk-violation');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('ROLE');
    expect(items[0].textContent).toContain('bad role');
    items[0].click();
    expect(area(f).selectionStart).toBe(text.indexOf('{"code": "R"}'));
    items[1].click();
    expect(area(f).selectionStart).toBe(text.indexOf('{"code": "S"}'));
  });

  it('shows line and column of a 400 and moves the caret there', async () => {
    const f = create(null);
    const text = '{\n  "a": 1\n}';
    api['validateDefinition'].mockReturnValue(
      throwError(() => apiError({ status: 400, message: 'Unexpected token', line: 2, column: 3 })),
    );
    type(f, text);
    await vi.advanceTimersByTimeAsync(500);
    f.detectChanges();
    expect(el(f).querySelector('.sk-backend-error')?.textContent).toContain('Line 2, column 3');
    expect(area(f).selectionStart).toBe(text.indexOf('\n', 0) + 3);
  });

  it('saves a new definition with POST and becomes a clean saved DRAFT at the new id', () => {
    const f = create(null);
    btn(f, 'sk-save').click();
    f.detectChanges();
    expect(api['createDefinition']).toHaveBeenCalledWith(area(f).value);
    expect(navigate).toHaveBeenCalledWith(['/admin/editor', 'id-d'], { replaceUrl: true });
    expect(btn(f, 'sk-publish').disabled).toBe(false);
    expect(f.componentInstance.hasUnsavedChanges()).toBe(false);
  });

  it('saves a DRAFT with PUT', () => {
    const f = create('id-d');
    f.componentInstance.setText(area(f).value + ' ');
    f.detectChanges();
    expect(btn(f, 'sk-publish').disabled).toBe(true);
    btn(f, 'sk-save').click();
    expect(api['updateDefinition']).toHaveBeenCalledWith('id-d', expect.any(String));
    expect(navigate).not.toHaveBeenCalled();
  });

  it('saves a PUBLISHED version with the edit endpoint and disables Publish for it', () => {
    api['definitionInfo'].mockReturnValue(of(PUBLISHED));
    const f = create('id-p');
    expect(btn(f, 'sk-publish').disabled).toBe(true);
    btn(f, 'sk-save').click();
    expect(api['editPublished']).toHaveBeenCalledWith('NEW_APP', '1', expect.any(String));
    expect(navigate).toHaveBeenCalledWith(['/admin/editor', 'id-d'], { replaceUrl: true });
  });

  it('enables Publish only for a clean saved DRAFT and publishes it', () => {
    const f = create('id-d');
    expect(btn(f, 'sk-publish').disabled).toBe(false);
    f.componentInstance.setText('{}');
    f.detectChanges();
    expect(btn(f, 'sk-publish').disabled).toBe(true);
  });

  it('shows the PUBLISHED state after a successful publish', () => {
    const f = create('id-d');
    btn(f, 'sk-publish').click();
    f.detectChanges();
    expect(api['publish']).toHaveBeenCalledWith('id-d');
    expect(el(f).textContent).toContain('PUBLISHED');
    expect(message.success).toHaveBeenCalled();
    expect(btn(f, 'sk-publish').disabled).toBe(true);
  });

  it('shows the message of a 409 on publish', () => {
    api['publish'].mockReturnValue(throwError(() => apiError({ status: 409, message: 'Already published' })));
    const f = create('id-d');
    btn(f, 'sk-publish').click();
    f.detectChanges();
    expect(el(f).querySelector('.sk-notice')?.textContent).toContain('Already published');
  });

  it('shows the report of a 422 on save', () => {
    api['updateDefinition'].mockReturnValue(
      throwError(() =>
        apiError({ status: 422, message: 'Invalid', violations: [{ field: 'roles[R]', rule: 'UNKNOWN', message: 'nope' }] }),
      ),
    );
    const f = create('id-d');
    btn(f, 'sk-save').click();
    f.detectChanges();
    expect(el(f).querySelector('button.sk-violation')?.textContent).toContain('nope');
  });
});

describe('unsavedChangesGuard', () => {
  function run(dirty: boolean, answer: boolean): { result: unknown; confirm: ReturnType<typeof vi.fn> } {
    const confirm = vi.fn(() => answer);
    TestBed.configureTestingModule({ providers: [{ provide: UnsavedChangesPrompt, useValue: { confirm } }] });
    const result = TestBed.runInInjectionContext(() =>
      unsavedChangesGuard({ hasUnsavedChanges: () => dirty }, null as never, null as never, null as never),
    );
    return { result, confirm };
  }

  it('asks for confirmation when there are unsaved changes', () => {
    const declined = run(true, false);
    expect(declined.confirm).toHaveBeenCalledTimes(1);
    expect(declined.result).toBe(false);
  });

  it('does not ask when clean', () => {
    const clean = run(false, false);
    expect(clean.confirm).not.toHaveBeenCalled();
    expect(clean.result).toBe(true);
  });
});

describe('editor routes', () => {
  it('registers lazy, guarded editor/new and editor/:id and keeps the empty route', () => {
    expect(adminChildren[0].path).toBe('');
    for (const path of ['editor/new', 'editor/:id']) {
      const r = adminChildren.find((x) => x.path === path);
      expect(r?.loadComponent).toBeTypeOf('function');
      expect(r?.canDeactivate).toEqual([unsavedChangesGuard]);
    }
  });
});
