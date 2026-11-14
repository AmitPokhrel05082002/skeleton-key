import { Routes } from '@angular/router';
import { NotAuthorisedPage } from './features/errors/not-authorised.page';
import { NotFoundPage } from './features/errors/not-found.page';
import { RootRedirectPage } from './features/errors/root-redirect.page';

export const routes: Routes = [
  // The explicit /app/:app routes are added by later tasks and always win over the Default_App.
  { path: '', pathMatch: 'full', component: RootRedirectPage },
  { path: 'not-authorised', component: NotAuthorisedPage },
  { path: '**', component: NotFoundPage },
];
