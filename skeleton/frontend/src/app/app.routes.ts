import { Routes } from '@angular/router';
import { RootRedirectPage } from './features/errors/root-redirect.page';

export const routes: Routes = [
  // The explicit /app/:app routes are added by later tasks and always win over the Default_App.
  { path: '', pathMatch: 'full', component: RootRedirectPage },
];
