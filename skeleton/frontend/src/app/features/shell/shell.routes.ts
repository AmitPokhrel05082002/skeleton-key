import { Routes } from '@angular/router';
import { ViewHostPage } from '../views/view-host.page';
import { AppHomeComponent } from './app-home.component';

/**
 * Children of `/app/:app`. Later tasks append `records/:entity/:id`,
 * `forms/:form` and `admin/...` here.
 */
export const shellChildren: Routes = [
  { path: '', pathMatch: 'full', component: AppHomeComponent },
  { path: 'views/:view', component: ViewHostPage },
];
