import { signal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalService } from 'ng-zorro-antd/modal';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/api/api.service';
import type { ApiError, UserPage, UserView } from '../../../core/api/api.types';
import { SessionService } from '../../../core/auth/session.service';
import { DEACTIVATE_HINT, UsersPageComponent } from './users-page.component';

const user = (id: string, active = true): UserView => ({
  id,
  username: `user-${id}`,
  displayName: `User ${id}`,
  active,
  roles: ['CLERK'],
  createdAt: '2026-11-01T08:00:00.000Z',
});

const page = (items: UserView[]): UserPage => ({ items, page: 0, size: 20, total: items.length, sort: 'createdAt,desc' });

interface ConfirmSpy {
  (o: { nzContent?: string; nzOnOk: () => void }): void;
  calls: { nzContent?: string }[];
}

function confirmSpy(accept: boolean): ConfirmSpy {
  const calls: { nzContent?: string }[] = [];
  const fn = ((o: { nzContent?: string; nzOnOk: () => void }) => {
    calls.push(o);
    if (accept) {
      o.nzOnOk();
    }
  }) as ConfirmSpy;
  fn.calls = calls;
  return fn;
}

function setup(api: Record<string, unknown>, confirm: ConfirmSpy = confirmSpy(true)) {
  const message = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: { roleOptions: () => of([]), ...api } },
      { provide: SessionService, useValue: { session: signal({ userId: 'me', displayName: 'Me', roles: [] }) } },
      { provide: NzMessageService, useValue: message },
    ],
  });
  // NzModalModule brings its own NzModalService into the standalone injector; a component provider wins over it.
  TestBed.overrideComponent(UsersPageComponent, {
    add: { providers: [{ provide: NzModalService, useValue: { confirm } }] },
  });
  const fixture = TestBed.createComponent(UsersPageComponent);
  fixture.detectChanges();
  return { fixture, message, confirm };
}

const el = (f: ComponentFixture<UsersPageComponent>) => f.nativeElement as HTMLElement;
const switchFor = (f: ComponentFixture<UsersPageComponent>, row: number) =>
  el(f).querySelectorAll('nz-switch')[row] as HTMLElement;

describe('UsersPageComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('renders users with their roles and never shows a password field', () => {
    const { fixture } = setup({ listUsers: () => of(page([user('a'), user('b')])) });
    expect(el(fixture).querySelectorAll('td.sk-username').length).toBe(2);
    expect(el(fixture).textContent).toContain('user-a');
    expect(el(fixture).textContent).toContain('CLERK');
    expect(el(fixture).querySelector('input[type="password"]')).toBeNull();
  });

  it('debounces the search box by 300 ms and resets to the first page', () => {
    const listUsers = vi.fn(() => of(page([])));
    const { fixture } = setup({ listUsers });
    listUsers.mockClear();
    const input = el(fixture).querySelector('input.sk-search') as HTMLInputElement;
    input.value = 'as';
    input.dispatchEvent(new Event('input'));
    input.value = 'asha';
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(299);
    expect(listUsers).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(listUsers).toHaveBeenCalledTimes(1);
    expect(listUsers).toHaveBeenCalledWith(expect.objectContaining({ q: 'asha', page: 0 }));
  });

  it('asks for confirmation with the sign-out hint before deactivating, then sends only active', () => {
    const updateUser = vi.fn(() => of(user('a', false)));
    const { fixture, confirm } = setup({ listUsers: () => of(page([user('a')])), updateUser });
    switchFor(fixture, 0).click();
    fixture.detectChanges();
    expect(confirm.calls.map((c) => c.nzContent)).toEqual([DEACTIVATE_HINT]);
    expect(updateUser).toHaveBeenCalledWith('a', { active: false });
  });

  it('does not deactivate when the confirmation is cancelled', () => {
    const updateUser = vi.fn();
    const { fixture } = setup({ listUsers: () => of(page([user('a')])), updateUser }, confirmSpy(false));
    switchFor(fixture, 0).click();
    expect(updateUser).not.toHaveBeenCalled();
  });

  it('keeps the old state and shows the API message when the change fails', () => {
    const err: ApiError = { status: 409, code: 'SELF_LOCKOUT', message: 'Not allowed', violations: [] };
    const { fixture, message } = setup({
      listUsers: () => of(page([user('a', false)])),
      updateUser: () => throwError(() => err),
    });
    switchFor(fixture, 0).click();
    fixture.detectChanges();
    expect(message.error).toHaveBeenCalledWith('Not allowed');
    expect(switchFor(fixture, 0).querySelector('button')?.classList.contains('ant-switch-checked')).toBe(false);
  });

  it('disables the active switch on the signed-in user\'s own row', () => {
    const updateUser = vi.fn();
    const { fixture } = setup({ listUsers: () => of(page([user('me'), user('b')])), updateUser });
    const own = switchFor(fixture, 0).querySelector('button') as HTMLButtonElement;
    expect(own.disabled).toBe(true);
    switchFor(fixture, 0).click();
    expect(updateUser).not.toHaveBeenCalled();
  });

  it('shows a clear message on 403', () => {
    const err: ApiError = { status: 403, code: 'FORBIDDEN', message: 'Access denied', violations: [] };
    const { fixture } = setup({ listUsers: () => throwError(() => err) });
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('not authorised to manage users');
  });
});
