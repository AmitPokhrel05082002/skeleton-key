import { Injectable, inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';

/** Asks the user to confirm leaving with unsaved changes. Injectable so tests can replace it. */
@Injectable({ providedIn: 'root' })
export class UnsavedChangesPrompt {
  confirm(message: string): boolean {
    return window.confirm(message);
  }
}

/** Implemented by a routed component that can hold unsaved changes. */
export interface HasUnsavedChanges {
  hasUnsavedChanges(): boolean;
}

export const UNSAVED_CHANGES_MESSAGE = 'You have unsaved changes. Leave this page and discard them?';

/** `canDeactivate` guard of the editor routes (Requirement 14.13). */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) =>
  !component.hasUnsavedChanges() || inject(UnsavedChangesPrompt).confirm(UNSAVED_CHANGES_MESSAGE);
