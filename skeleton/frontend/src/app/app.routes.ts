import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/admin.guard';
import { authGuard } from './core/auth/auth.guard';
import { NotAuthorisedPage } from './features/errors/not-authorised.page';
import { NotFoundPage } from './features/errors/not-found.page';
import { RootRedirectPage } from './features/errors/root-redirect.page';

export const routes: Routes = [
  // The explicit /app/:app routes are added by later tasks and always win over the Default_App.
  { path: '', pathMatch: 'full', component: RootRedirectPage },
  { path: 'not-authorised', component: NotAuthorisedPage },
  {
    path: 'app/:app',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell.component').then((m) => m.ShellComponent),
    loadChildren: () => import('./features/shell/shell.routes').then((m) => m.shellChildren),
  },
  // Must stay before 'public/:app/:form' so "track" is not read as a form code.
  {
    path: 'public/:app/track',
    loadComponent: () => import('./features/public-track/track.page').then((m) => m.TrackPage),
  },
  {
    path: 'public/:app/:form',
    loadComponent: () =>
      import('./features/public-form/public-form.page').then((m) => m.PublicFormPage),
  },
  {
    path: 'admin',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/admin-shell.component').then((m) => m.AdminShellComponent),
    loadChildren: () => import('./features/admin/admin.routes').then((m) => m.adminChildren),
  },
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage),
  },
  { path: '**', component: NotFoundPage },
];
