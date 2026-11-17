import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { NzMessageService } from 'ng-zorro-antd/message';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type {
  ActionInfo,
  ApiError,
  HistoryEntry,
  InstanceView,
  RecordSummary,
  RestrictedDefinition,
} from '../../core/api/api.types';
import { RecordDetailComponent } from './record-detail.component';

const def: RestrictedDefinition = {
  app: { code: 'GRV', name: 'Grievance' },
  roles: [{ code: 'CLERK', label: 'Clerk' }],
  entities: [
    {
      code: 'GRIEVANCE',
      label: 'Grievance',
      fields: [
        { code: 'title', label: 'Title', type: 'TEXT', required: true },
        { code: 'detail', label: 'Detail', type: 'TEXT' },
      ],
    },
  ],
  forms: [],
  views: [],
  workflows: [],
};

const act = (code: string, style: ActionInfo['style'], extra: Partial<ActionInfo> = {}): ActionInfo => ({
  code,
  label: code.toLowerCase(),
  style,
  remarks: 'hidden',
  attachment: 'hidden',
  ...extra,
});

const record = (extra: Partial<RecordSummary> = {}): RecordSummary => ({
  id: 'r1',
  referenceNo: 'GRV-1',
  instanceId: 'i1',
  stepLabel: 'Review',
  statusColor: '#1677ff',
  data: { title: 'Broken light' },
  ...extra,
});

const instance = (extra: Partial<InstanceView> = {}): InstanceView => ({
  instanceId: 'i1',
  recordId: 'r1',
  step: 'REVIEW',
  status: 'In review',
  ended: false,
  assignee: 'u1',
  assigneeName: 'Asha',
  actions: [act('SAVE', 'default'), act('APPROVE', 'primary'), act('REJECT', 'danger')],
  fieldRules: { title: 'optional', detail: 'optional' },
  ...extra,
});

const entry = (kind: string, extra: Partial<HistoryEntry> = {}): HistoryEntry => ({
  kind,
  eventTime: '2026-01-01T00:00:00Z',
  timeInStepMs: 0,
  ...extra,
});

const apiErr = (status: number, extra: Partial<ApiError> = {}): ApiError => ({
  status,
  code: 'X',
  message: 'server says no',
  violations: [],
  ...extra,
});

function setup(api: Record<string, unknown>) {
  const message = { success: vi.fn(), error: vi.fn(), remove: vi.fn() };
  const calls = {
    getRecord: vi.fn(() => of(record())),
    getInstance: vi.fn(() => of(instance())),
    history: vi.fn(() => of<HistoryEntry[]>([])),
    runAction: vi.fn(() => of(instance())),
  };
  const merged = { definition: () => of(def), ...calls, ...api } as unknown as Record<
    string,
    ReturnType<typeof vi.fn>
  >;
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: merged },
      { provide: NzMessageService, useValue: message },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { paramMap: convertToParamMap({ entity: 'GRIEVANCE', id: 'r1' }) },
          paramMap: of(convertToParamMap({ entity: 'GRIEVANCE', id: 'r1' })),
          parent: {
            snapshot: { paramMap: convertToParamMap({ app: 'GRV' }) },
            paramMap: of(convertToParamMap({ app: 'GRV' })),
          },
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(RecordDetailComponent);
  fixture.detectChanges();
  return { fixture, message, api: merged };
}

const el = (f: ComponentFixture<RecordDetailComponent>) => f.nativeElement as HTMLElement;
const buttons = (f: ComponentFixture<RecordDetailComponent>) =>
  Array.from(el(f).querySelectorAll<HTMLButtonElement>('button.sk-action-btn'));
const titleInput = (f: ComponentFixture<RecordDetailComponent>) => el(f).querySelector('input') as HTMLInputElement;

function type(f: ComponentFixture<RecordDetailComponent>, input: HTMLInputElement | HTMLTextAreaElement, value: string) {
  input.value = value;
  input.dispatchEvent(new Event('input'));
  f.detectChanges();
}

describe('RecordDetailComponent', () => {
  it('shows the header and renders buttons in order with their styles', () => {
    const { fixture: f } = setup({});
    const text = el(f).textContent ?? '';
    expect(text).toContain('GRV-1');
    expect(text).toContain('Review');
    expect(text).toContain('In review');
    expect(text).toContain('Asha');
    expect(buttons(f).map((b) => b.textContent?.trim())).toEqual(['save', 'approve', 'reject']);
    expect(buttons(f)[0].classList).not.toContain('ant-btn-primary');
    expect(buttons(f)[1].classList).toContain('ant-btn-primary');
    expect(buttons(f)[2].classList).toContain('ant-btn-dangerous');
    expect(titleInput(f).value).toBe('Broken light');
  });

  it('opens the dialog for an action with remarks and sends the request only after confirm', () => {
    const { fixture: f, api } = setup({
      getInstance: () => of(instance({ actions: [act('APPROVE', 'primary', { remarks: 'required' })] })),
    });
    buttons(f)[0].click();
    f.detectChanges();
    expect(el(f).querySelector('sk-action-dialog')).not.toBeNull();
    expect(api['runAction']).not.toHaveBeenCalled();
    type(f, titleInput(f), 'Fixed light');
    type(f, el(f).querySelector('#sk-ad-remarks') as HTMLTextAreaElement, 'ok');
    (el(f).querySelector('.sk-ad-confirm-btn') as HTMLButtonElement).click();
    f.detectChanges();
    expect(api['runAction']).toHaveBeenCalledWith('i1', 'APPROVE', { fields: { title: 'Fixed light' }, remarks: 'ok' });
    expect(el(f).querySelector('sk-action-dialog')).toBeNull();
  });

  it('runs an action without remarks or attachment immediately', () => {
    const { fixture: f, api } = setup({});
    buttons(f)[1].click();
    expect(api['runAction']).toHaveBeenCalledWith('i1', 'APPROVE', { fields: {} });
    expect(el(f).querySelector('sk-action-dialog')).toBeNull();
  });

  it('replaces the instance, reloads record and history and shows a message on success', () => {
    const ended = instance({ ended: true, status: 'Done', actions: [] });
    const { fixture: f, api, message } = setup({ runAction: vi.fn(() => of(ended)) });
    expect(api['getRecord']).toHaveBeenCalledTimes(1);
    buttons(f)[1].click();
    f.detectChanges();
    expect(api['getRecord']).toHaveBeenCalledTimes(2);
    expect(api['history']).toHaveBeenCalledTimes(2);
    expect(message.success).toHaveBeenCalledTimes(1);
    expect(el(f).textContent).toContain('Done');
    expect(buttons(f)).toHaveLength(0);
  });

  it('shows the 409 message, reloads the instance and keeps typed values', () => {
    const { fixture: f, api, message } = setup({ runAction: () => throwError(() => apiErr(409)) });
    type(f, titleInput(f), 'Typed');
    buttons(f)[1].click();
    f.detectChanges();
    expect(message.error).toHaveBeenCalledWith('server says no');
    expect(api['getInstance']).toHaveBeenCalledTimes(2);
    expect(titleInput(f).value).toBe('Typed');
  });

  it('maps 422 violations to the dialog input and keeps the dialog open', () => {
    const err = apiErr(422, { violations: [{ field: 'remarks', rule: 'required', message: 'Remarks needed' }] });
    const { fixture: f } = setup({
      getInstance: () => of(instance({ actions: [act('APPROVE', 'primary', { remarks: 'optional' })] })),
      runAction: () => throwError(() => err),
    });
    buttons(f)[0].click();
    f.detectChanges();
    (el(f).querySelector('.sk-ad-confirm-btn') as HTMLButtonElement).click();
    f.detectChanges();
    expect(el(f).querySelector('sk-action-dialog')).not.toBeNull();
    expect(el(f).querySelector('#sk-ad-remarks-error')?.textContent).toContain('Remarks needed');
  });

  it('shows a generic message with the request id on a 5xx', () => {
    const { fixture: f, message } = setup({
      runAction: () => throwError(() => apiErr(500, { message: 'stack trace', requestId: 'req-7' })),
    });
    buttons(f)[1].click();
    f.detectChanges();
    const shown = String(message.error.mock.calls[0][0]);
    expect(shown).toContain('req-7');
    expect(shown).not.toContain('stack trace');
  });

  it('shows fields with entity required flags and no actions or history for a null instanceId', () => {
    const { fixture: f, api } = setup({ getRecord: () => of(record({ instanceId: null })) });
    expect(api['getInstance']).not.toHaveBeenCalled();
    expect(api['history']).not.toHaveBeenCalled();
    expect(buttons(f)).toHaveLength(0);
    expect(el(f).querySelector('sk-history-timeline')).toBeNull();
    expect(titleInput(f).value).toBe('Broken light');
    expect(titleInput(f).disabled).toBe(true);
  });

  it('labels an ended instance as closed and shows no buttons', () => {
    const { fixture: f } = setup({ getInstance: () => of(instance({ ended: true })) });
    expect(el(f).querySelector('.sk-record-closed')?.textContent).toContain('Closed');
    expect(buttons(f)).toHaveLength(0);
  });

  it('renders the history in backend order', () => {
    const { fixture: f } = setup({
      history: () => of([entry('STARTED'), entry('ACTION'), entry('ESCALATED')]),
    });
    const kinds = Array.from(el(f).querySelectorAll('.sk-h-kind')).map((k) => k.textContent);
    expect(kinds).toEqual(['STARTED', 'ACTION', 'ESCALATED']);
  });

  it('shows not found with a link to the app home on 404', () => {
    const { fixture: f } = setup({ getRecord: () => throwError(() => apiErr(404)) });
    expect(el(f).textContent).toContain('Not found');
    expect(el(f).querySelector('a')?.getAttribute('href')).toBe('/app/GRV');
  });

  describe('late action response after navigating away', () => {
    function navigated() {
      const params = new BehaviorSubject(convertToParamMap({ entity: 'GRIEVANCE', id: 'r1' }));
      const late = new Subject<InstanceView>();
      const message = { success: vi.fn(), error: vi.fn(), remove: vi.fn() };
      const r2 = record({ id: 'r2', referenceNo: 'GRV-2', instanceId: 'i2', data: { title: 'Second' } });
      const i2 = instance({
        instanceId: 'i2',
        recordId: 'r2',
        status: 'Waiting',
        actions: [act('SAVE', 'default')],
      });
      const getRecord = vi.fn((_a: string, _e: string, id: string) => of(id === 'r1' ? record() : r2));
      const getInstance = vi.fn((id: string) => of(id === 'i1' ? instance() : i2));
      const history = vi.fn((id: string) => of(id === 'i2' ? [entry('STARTED')] : []));
      const runAction = vi.fn(() => late);
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          { provide: ApiService, useValue: { definition: () => of(def), getRecord, getInstance, history, runAction } },
          { provide: NzMessageService, useValue: message },
          {
            provide: ActivatedRoute,
            useValue: { paramMap: params, parent: { paramMap: of(convertToParamMap({ app: 'GRV' })) } },
          },
        ],
      });
      const f = TestBed.createComponent(RecordDetailComponent);
      f.detectChanges();
      buttons(f)[1].click();
      params.next(convertToParamMap({ entity: 'GRIEVANCE', id: 'r2' }));
      f.detectChanges();
      return { f, late, message, getRecord, getInstance, history };
    }

    function expectUntouched(
      f: ComponentFixture<RecordDetailComponent>,
      c: ReturnType<typeof navigated>,
    ) {
      expect(el(f).textContent).toContain('GRV-2');
      expect(el(f).textContent).toContain('Waiting');
      expect(buttons(f).map((b) => b.textContent?.trim())).toEqual(['save']);
      expect(buttons(f)[0].disabled).toBe(false);
      expect(el(f).querySelectorAll('.sk-h-kind')).toHaveLength(1);
      expect(c.getRecord).toHaveBeenCalledTimes(2);
      expect(c.history).toHaveBeenCalledTimes(2);
      expect(c.message.success).not.toHaveBeenCalled();
      expect(c.message.error).not.toHaveBeenCalled();
    }

    it('ignores a late success response', () => {
      const c = navigated();
      c.late.next(instance({ status: 'Done', ended: true, actions: [] }));
      c.f.detectChanges();
      expectUntouched(c.f, c);
      expect(c.getInstance).toHaveBeenCalledTimes(2);
    });

    it('ignores a late 409 error and does not reload the instance', () => {
      const c = navigated();
      c.late.error(apiErr(409));
      c.f.detectChanges();
      expectUntouched(c.f, c);
      expect(c.getInstance).toHaveBeenCalledTimes(2);
    });
  });

  it('shows the new record after navigating and drops a late response for the previous one', () => {
    const params = new BehaviorSubject(convertToParamMap({ entity: 'GRIEVANCE', id: 'r1' }));
    const a = new Subject<RecordSummary>();
    const b = new Subject<RecordSummary>();
    const getRecord = vi.fn((_app: string, _entity: string, id: string) => (id === 'r1' ? a : b));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            definition: () => of(def),
            getRecord,
            getInstance: () => of(instance()),
            history: () => of<HistoryEntry[]>([]),
          },
        },
        { provide: NzMessageService, useValue: { success: vi.fn(), error: vi.fn(), remove: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: params,
            parent: { paramMap: of(convertToParamMap({ app: 'GRV' })) },
          },
        },
      ],
    });
    const f = TestBed.createComponent(RecordDetailComponent);
    f.detectChanges();
    params.next(convertToParamMap({ entity: 'GRIEVANCE', id: 'r2' }));
    f.detectChanges();
    expect(getRecord).toHaveBeenCalledTimes(2);
    b.next(record({ id: 'r2', referenceNo: 'GRV-2', data: { title: 'Second' } }));
    b.complete();
    f.detectChanges();
    a.next(record({ id: 'r1', referenceNo: 'GRV-1', data: { title: 'First' } }));
    a.complete();
    f.detectChanges();
    expect(el(f).textContent).toContain('GRV-2');
    expect(el(f).textContent).not.toContain('GRV-1');
    expect(titleInput(f).value).toBe('Second');
  });
});
