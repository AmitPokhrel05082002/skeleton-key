import { Routes } from '@angular/router';

/**
 * Children of `/app/:app`. Later tasks append `records/:entity/:id`,
 * `forms/:form/new` and `admin/...` here.
 */
export const shellChildren: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./app-home.component').then((m) => m.AppHomeComponent),
  },
  {
    path: 'views/:view',
    loadComponent: () => import('../views/view-host.page').then((m) => m.ViewHostPage),
  },
  {
    path: 'forms/:form/new',
    loadComponent: () => import('../form/authed-form.page').then((m) => m.AuthedFormPage),
  },
  {
    path: 'records/:entity/:id',
    loadComponent: () => import('../record/record-detail.component').then((m) => m.RecordDetailComponent),
  },
];
