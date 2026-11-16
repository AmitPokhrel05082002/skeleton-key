import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { ShellComponent } from './features/shell/shell.component';
import { shellChildren } from './features/shell/shell.routes';
import { NotAuthorisedPage } from './features/errors/not-authorised.page';
import { NotFoundPage } from './features/errors/not-found.page';
import { RootRedirectPage } from './features/errors/root-redirect.page';
import { LoginPage } from './features/login/login.page';
import { PublicFormPage } from './features/public-form/public-form.page';

export const routes: Routes = [
  // The explicit /app/:app routes are added by later tasks and always win over the Default_App.
  { path: '', pathMatch: 'full', component: RootRedirectPage },
  { path: 'not-authorised', component: NotAuthorisedPage },
  { path: 'app/:app', component: ShellComponent, canActivate: [authGuard], children: shellChildren },
  { path: 'public/:app/:form', component: PublicFormPage },
  { path: 'login', component: LoginPage },
  { path: '**', component: NotFoundPage },
];
