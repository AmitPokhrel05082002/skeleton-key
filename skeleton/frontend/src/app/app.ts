import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { RuntimeConfigService } from './core/config/runtime-config.service';
import { ConfigErrorPage } from './features/errors/config-error.page';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ConfigErrorPage],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  protected readonly config = inject(RuntimeConfigService);
}
