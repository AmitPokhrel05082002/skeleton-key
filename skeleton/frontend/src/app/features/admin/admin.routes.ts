import { Routes } from '@angular/router';
import { unsavedChangesGuard } from './unsaved-changes.guard';

/**
 * Children of `/admin`.
 *
 * Editor routes (both lazy, both guarded against losing unsaved changes):
 * - `/admin/editor/new`  opens the editor with `minimalDefinitionTemplate()` built by the editor itself.
 * - `/admin/editor/:id`  opens the stored version with that id (`getDefinition(id)` + `definitionInfo(id)`).
 * `editor/new` is listed before `editor/:id` so `new` is not read as an id.
 */
export const adminChildren: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () =>
      import('./definitions-list.component').then((m) => m.DefinitionsListComponent),
  },
  {
    path: 'editor/new',
    loadComponent: () => import('./editor-page.component').then((m) => m.EditorPageComponent),
    canDeactivate: [unsavedChangesGuard],
  },
  {
    path: 'editor/:id',
    loadComponent: () => import('./editor-page.component').then((m) => m.EditorPageComponent),
    canDeactivate: [unsavedChangesGuard],
  },
];
